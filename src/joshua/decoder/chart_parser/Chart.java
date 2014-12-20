package joshua.decoder.chart_parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import joshua.corpus.Vocabulary;
import joshua.corpus.syntax.SyntaxTree;
import joshua.decoder.Decoder;
import joshua.decoder.JoshuaConfiguration;
import joshua.decoder.chart_parser.CubePruneState;
import joshua.decoder.chart_parser.DotChart.DotNode;
import joshua.decoder.ff.FeatureFunction;
import joshua.decoder.ff.SourceDependentFF;
import joshua.decoder.ff.tm.AbstractGrammar;
import joshua.decoder.ff.tm.Grammar;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.ff.tm.RuleCollection;
import joshua.decoder.ff.tm.Trie;
import joshua.decoder.ff.tm.hash_based.MemoryBasedBatchGrammar;
import joshua.decoder.hypergraph.HGNode;
import joshua.decoder.hypergraph.HyperGraph;
import joshua.decoder.hypergraph.KBestExtractor;
import joshua.decoder.segment_file.ParsedSentence;
import joshua.decoder.segment_file.Sentence;
import joshua.lattice.Arc;
import joshua.lattice.Lattice;
import joshua.lattice.Node;
import joshua.util.ChartSpan;

/**
 * Chart class this class implements chart-parsing: (1) seeding the chart (2)
 * cky main loop over bins, (3) identify applicable rules in each bin
 * 
 * Note: the combination operation will be done in Cell
 * 
 * Signatures of class: Cell: i, j SuperNode (used for CKY check): i,j, lhs
 * HGNode ("or" node): i,j, lhs, edge ngrams HyperEdge ("and" node)
 * 
 * index of sentences: start from zero index of cell: cell (i,j) represent span
 * of words indexed [i,j-1] where i is in [0,n-1] and j is in [1,n]
 * 
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @author Matt Post <post@cs.jhu.edu>
 */

public class Chart {

  private final JoshuaConfiguration joshuaConfiguration;
  // ===========================================================
  // Statistics
  // ===========================================================

  /**
   * how many items have been pruned away because its cost is greater than the
   * cutoff in calling chart.add_deduction_in_chart()
   */
  int nMerged = 0;
  int nAdded = 0;
  int nDotitemAdded = 0; // note: there is no pruning in dot-item

  public int sentenceID() {
    if (sentence != null)
      return sentence.id();
    return -1;
  }

  // ===============================================================
  // Private instance fields (maybe could be protected instead)
  // ===============================================================
  private ChartSpan<Cell> cells; // note that in some cell, it might be null
  private int sourceLength;
  private List<FeatureFunction> featureFunctions;
  private Grammar[] grammars;
  private DotChart[] dotcharts; // each grammar should have a dotchart
                                // associated with it
  private Cell goalBin;
  private int goalSymbolID = -1;
  private Lattice<Integer> inputLattice;

  public KBestExtractor kBestExtractor;

  private Sentence sentence = null;
  private SyntaxTree parseTree;

  private ManualConstraintsHandler manualConstraintsHandler;
  private StateConstraint stateConstraint;

  private NonterminalMatcher nonterminalMatcher;
  
  // ===============================================================
  // Static fields
  // ===============================================================

  // ===========================================================
  // Logger
  // ===========================================================
  private static final Logger logger = Logger.getLogger(Chart.class.getName());

  // ===============================================================
  // Constructors
  // ===============================================================

  /*
   * TODO: Once the Segment interface is adjusted to provide a Lattice<String>
   * for the sentence() method, we should just accept a Segment instead of the
   * sentence, segmentID, and constraintSpans parameters. We have the symbol
   * table already, so we can do the integerization here instead of in
   * DecoderThread. GrammarFactory.getGrammarForSentence will want the
   * integerized sentence as well, but then we'll need to adjust that interface
   * to deal with (non-trivial) lattices too. Of course, we get passed the
   * grammars too so we could move all of that into here.
   */

  public Chart(Sentence sentence, List<FeatureFunction> featureFunctions, Grammar[] grammars,
      String goalSymbol, JoshuaConfiguration config) {
    this.joshuaConfiguration = config;
    this.inputLattice = sentence.intLattice();
    this.sourceLength = inputLattice.size() - 1;
    this.featureFunctions = featureFunctions;

    this.sentence = sentence;

    // TODO: OOV handling no longer handles parse tree input (removed after commit 748eb69714b26dd67cba8e7c25a294347603bede)
    this.parseTree = null;
    if (sentence instanceof ParsedSentence)
      this.parseTree = ((ParsedSentence) sentence).syntaxTree();

    this.cells = new ChartSpan<Cell>(sourceLength, null);

    this.goalSymbolID = Vocabulary.id(goalSymbol);
    this.goalBin = new Cell(this, this.goalSymbolID);

    /*
     * Create the kbest extractor. This is only actually used in the chart if we
     * are decoding with non-local features.
     */
    this.kBestExtractor = new KBestExtractor(sentence, featureFunctions, Decoder.weights, false,
        config);

    /* Create the grammars, leaving space for the OOV grammar. */
    this.grammars = new Grammar[grammars.length + 1];
    for (int i = 0; i < grammars.length; i++)
      this.grammars[i + 1] = grammars[i];

    MemoryBasedBatchGrammar oovGrammar = new MemoryBasedBatchGrammar("oov", config);
    AbstractGrammar.addOOVRules(oovGrammar, sentence.intLattice(), featureFunctions, joshuaConfiguration.true_oovs_only);
    this.grammars[0] = oovGrammar; 
        
    // each grammar will have a dot chart
    this.dotcharts = new DotChart[this.grammars.length];
    for (int i = 0; i < this.grammars.length; i++)
      this.dotcharts[i] = new DotChart(this.inputLattice, this.grammars[i], this,
          NonterminalMatcher.createNonterminalMatcher(logger, config),
          this.grammars[i].isRegexpGrammar());

    // Begin to do initialization work

    // TODO: which grammar should we use to create a manual rule?
    // TODO: I don't think this is really used
    manualConstraintsHandler = new ManualConstraintsHandler(this, grammars[grammars.length - 1],
        sentence.constraints());
    
    stateConstraint = null;
    if (sentence.target() != null)
      // stateConstraint = new StateConstraint(sentence.target());
      stateConstraint = new StateConstraint(Vocabulary.START_SYM + " " + sentence.target() + " "
          + Vocabulary.STOP_SYM);

    /* Find the SourceDependent feature and give it access to the sentence. */
    for (FeatureFunction ff : this.featureFunctions)
      if (ff instanceof SourceDependentFF)
        ((SourceDependentFF) ff).setSource(sentence);
    
    nonterminalMatcher = NonterminalMatcher.createNonterminalMatcher(logger, config);

    logger.fine("Finished seeding chart.");
  }

  /**
   * Manually set the goal symbol ID. The constructor expects a String
   * representing the goal symbol, but there may be time (say, for example, in
   * the second pass of a synchronous parse) where we want to set the goal
   * symbol to a particular ID (regardless of String representation).
   * <p>
   * This method should be called before expanding the chart, as chart expansion
   * depends on the goal symbol ID.
   * 
   * @param i the id of the goal symbol to use
   */
  public void setGoalSymbolID(int i) {
    this.goalSymbolID = i;
    this.goalBin = new Cell(this, i);
    return;
  }

  // ===============================================================
  // The primary method for filling in the chart
  // ===============================================================

  /**
   * Construct the hypergraph with the help from DotChart using cube pruning.
   * Cube pruning occurs at the span level, with all completed rules from the
   * dot chart competing against each other; that is, rules with different
   * source sides *and* rules sharing a source side but with different target
   * sides are all in competition with each other.
   * 
   * Terminal rules are added to the chart directly.
   * 
   * Rules with nonterminals are added to the list of candidates. The candidates
   * list is seeded with the list of all rules and, for each nonterminal in the
   * rule, the 1-best tail node for that nonterminal and subspan. If the maximum
   * arity of a rule is R, then the dimension of the hypercube is R + 1, since
   * the first dimension is used to record the rule.
   */
  private void completeSpan(int i, int j) {

    /* STEP 1: create the heap, and seed it with all of the candidate states */
    PriorityQueue<CubePruneState> candidates = new PriorityQueue<CubePruneState>();

    /*
     * Look at all the grammars, seeding the chart with completed rules from the
     * DotChart
     */
    for (int g = 0; g < grammars.length; g++) {
      if (!grammars[g].hasRuleForSpan(i, j, inputLattice.distance(i, j))
          || null == dotcharts[g].getDotCell(i, j))
        continue;

      // for each rule with applicable rules
      for (DotNode dotNode : dotcharts[g].getDotCell(i, j).getDotNodes()) {
        RuleCollection ruleCollection = dotNode.getRuleCollection();
        if (ruleCollection == null)
          continue;

        List<Rule> rules = ruleCollection.getSortedRules(this.featureFunctions);
        SourcePath sourcePath = dotNode.getSourcePath();

        if (null == rules || rules.size() == 0)
          continue;

        int arity = ruleCollection.getArity();

        if (arity == 0) {
          /* Terminal productions are added directly to the chart */
          for (Rule rule : rules) {
/*
            // DON'T USE UNIQUE STATE CHECKING!!!  
            HyperEdge newEdge = new HyperEdge(rule, 0.0f, 0.0f, null, new SourcePath());
            HGNode newNode = new HGNode(i, j, rule.getLHS(), null, newEdge, 0.0f);
            DerivationState state = this.kBestExtractor.new DerivationState(newNode, newEdge, null, 0.0f, -1);

            ComputeNodeResult result = new ComputeNodeResult(this.featureFunctions, state, i, j,
                sourcePath, this.sentence);
*/
            
            ComputeNodeResult result = new ComputeNodeResult(this.featureFunctions, rule, null, i,
                j, sourcePath, this.sentence);

            if (stateConstraint == null || stateConstraint.isLegal(result.getDPStates()))
              getCell(i, j).addHyperEdgeInCell(result, rule, i, j, null, sourcePath, true);
          }
        } else {
          /* Productions with rank > 0 are subject to cube pruning */

          Rule bestRule = rules.get(0);

          List<HGNode> currentTailNodes = new ArrayList<HGNode>();
          List<SuperNode> superNodes = dotNode.getAntSuperNodes();
          for (SuperNode si : superNodes) {
            currentTailNodes.add(si.nodes.get(0));
          }

          /*
           * `ranks` records the current position in the cube. the 0th index is
           * the rule, and the remaining indices 1..N correspond to the tail
           * nodes (= nonterminals in the rule). These tail nodes are
           * represented by SuperNodes, which group together items with the same
           * nonterminal but different DP state (e.g., language model state)
           */
          int[] ranks = new int[1 + superNodes.size()];
          Arrays.fill(ranks, 1);

          /*
          // DON'T USE UNIQUE STATE CHECKING!!!
          HyperEdge newEdge = new HyperEdge(bestRule, 0.0f, 0.0f, currentTailNodes,
              new SourcePath());
          HGNode newNode = new HGNode(i, j, bestRule.getLHS(), null, newEdge, 0.0f);
          DerivationState derivationState = kBestExtractor.getKthDerivation(newNode, 1);

          // Score the rule application
          ComputeNodeResult result = new ComputeNodeResult(featureFunctions, derivationState, i, j,
              sourcePath, this.sentence);
          */
          
          ComputeNodeResult result = new ComputeNodeResult(featureFunctions, bestRule, currentTailNodes, i, j, sourcePath, sentence);
          CubePruneState bestState = new CubePruneState(result, ranks, rules, currentTailNodes, dotNode);
          candidates.add(bestState);
        }
      }
    }

    applyCubePruning(i, j, candidates);
  }

  /**
   * Applies cube pruning over a span.
   * 
   * @param i
   * @param j
   * @param stateConstraint
   * @param candidates
   */
  private void applyCubePruning(int i, int j, PriorityQueue<CubePruneState> candidates) {

//    System.err.println(String.format("CUBEPRUNE: %d-%d with %d candidates", i, j, candidates.size()));
    
    /* There are multiple ways to reach each point in the cube, so short-circuit that. */
    HashSet<CubePruneState> visitedStates = new HashSet<CubePruneState>();
    
    int popLimit = joshuaConfiguration.pop_limit;
    int popCount = 0;
    while (candidates.size() > 0 && ((++popCount <= popLimit) || popLimit == 0)) {
      CubePruneState state = candidates.poll();

      DotNode dotNode = state.getDotNode();
      List<Rule> rules = state.rules;
      SourcePath sourcePath = dotNode.getSourcePath();
      List<SuperNode> superNodes = dotNode.getAntSuperNodes();

      /*
       * Add the hypothesis to the chart. This can only happen if (a) we're not
       * doing constrained decoding or (b) we are and the state is legal.
       */
      if (stateConstraint == null || stateConstraint.isLegal(state.getDPStates())) {
        getCell(i, j).addHyperEdgeInCell(state.computeNodeResult, state.getRule(), i, j,
            state.antNodes, sourcePath, true);
      }

      /*
       * Expand the hypothesis by walking down a step along each dimension of
       * the cube, in turn. k = 0 means we extend the rule being used; k > 0
       * expands the corresponding tail node.
       */

      // TODO: go through the derivation states
      for (int k = 0; k < state.ranks.length; k++) {
        
        /* Copy the current ranks, then extend the one we're looking at. */
        int[] nextRanks = new int[state.ranks.length];
        System.arraycopy(state.ranks, 0, nextRanks, 0, state.ranks.length);
        nextRanks[k]++;

        /*
         * We might have reached the end of something (list of rules or tail
         * nodes)
         */
        if ((k == 0 && nextRanks[k] > rules.size())
            || (k != 0 && nextRanks[k] > superNodes.get(k - 1).nodes.size()))
          continue;

        /* Use the updated ranks to assign the next rule and tail node. */
        Rule nextRule = rules.get(nextRanks[0] - 1);
        // HGNode[] nextAntNodes = new HGNode[state.antNodes.size()];
        List<HGNode> nextAntNodes = new ArrayList<HGNode>();
        for (int x = 0; x < state.ranks.length - 1; x++)
          nextAntNodes.add(superNodes.get(x).nodes.get(nextRanks[x + 1] - 1));

        /* Create the next state. */
        CubePruneState nextState = new CubePruneState(new ComputeNodeResult(featureFunctions,
            nextRule, nextAntNodes, i, j, sourcePath, this.sentence), nextRanks, rules,
            nextAntNodes, dotNode);
        
        /*
        // DON'T USE UNIQUE STATE CHECKING!!!
        // TODO: derivation state rank not set correctly
        HyperEdge newEdge = new HyperEdge(nextRule, 0.0f, 0.0f, nextTailNodes, new SourcePath());
        HGNode newNode = new HGNode(i, j, nextRule.getLHS(), null, newEdge, 0.0f);
        DerivationState nextDerivationState = kBestExtractor.getKthDerivation(newNode, 1);

        CubePruneState nextState = new CubePruneState(new ComputeNodeResult(featureFunctions,
            nextDerivationState, i, j, sourcePath, this.sentence), nextRanks, rules, nextTailNodes,
            dotNode);
        */

        /* Skip states that have been explored before. */
        if (visitedStates.contains(nextState))
          continue;

        visitedStates.add(nextState);
        candidates.add(nextState);
      }
    }
  }

  // Create a priority queue of candidates for each span under consideration
  private PriorityQueue<CubePruneState>[] allCandidates;

  /**
   * Translates the sentence using the CKY+ variation proposed in 
   * "A CYK+ Variant for SCFG Decoding Without A Dot Chart" (Sennrich, SSST 2014).
   */
  public HyperGraph expandSansDotChart() {
    for (int i = sourceLength - 1; i >= 0; i--) {
      allCandidates = new PriorityQueue[sourceLength - i + 2];
      for (int id = 0; id < allCandidates.length; id++)
        allCandidates[id] = new PriorityQueue<CubePruneState>();

      /* Add preterminals ending in a single word */
      for (int g = 0; g < this.grammars.length; g++) {
        Node<Integer> node = sentence.getNode(i);
        for (Arc<Integer> arc : node.getOutgoingArcs()) {
          int word = arc.getLabel();
          Trie trie = this.grammars[g].getTrieRoot().match(word);
          if (trie != null && trie.hasRules()) {
            int j = arc.getHead().id();

            DotNode dotNode = new DotNode(i, j, trie, new ArrayList<SuperNode>(), new SourcePath().extend(arc));
            for (Rule rule: dotNode.getRuleCollection().getSortedRules(featureFunctions)) {
              ComputeNodeResult result = new ComputeNodeResult(this.featureFunctions, rule, null, 
                  dotNode.begin(), dotNode.end(), dotNode.getSourcePath(), this.sentence);
              if (stateConstraint == null || stateConstraint.isLegal(result.getDPStates()))
                getCell(i,j).addHyperEdgeInCell(result, rule, i, j, null, dotNode.getSourcePath(), false);
            }
          }
        }
      }
      
      for (int j = i + 1; j <= sourceLength; j++) {
        if (! sentence.hasPath(i,  j))
          continue;
        
        /* Recurse */
        for (int g = 0; g < this.grammars.length; g++) {
//          System.err.println(String.format("\n*** I=%d J=%d GRAMMAR=%d", i, j, g));
          consume(new DotNode(i, i, this.grammars[g].getTrieRoot(), new ArrayList<SuperNode>(), new SourcePath()), j-1);
        }

        // Now that we've accumulated all the candidates, apply cube pruning
        applyCubePruning(i, j, allCandidates[j - i]);

        // Add unary nodes
        addUnaryNodes(this.grammars, i, j);
      }
    }
    
    // transition_final: setup a goal item, which may have many deductions
    if (null == this.cells.get(0, sourceLength)
        || !this.goalBin.transitToGoal(this.cells.get(0, sourceLength), this.featureFunctions,
            this.sourceLength)) {
      logger.severe("No complete item in the Cell[0," + sourceLength + "]; possible reasons: "
          + "(1) your grammar does not have any valid derivation for the source sentence; "
          + "(2) too aggressive pruning.");
      return null;
    }

    return new HyperGraph(this.goalBin.getSortedNodes().get(0), -1, -1, this.sentence);
  }

  /**
   * Recursively consumes the trie, following input nodes, finding applicable rules and adding
   * them to bins for each span for later cube pruning.
   * 
   * @param dotNode data structure containing information about what's been already matched
   * @param l extension point we're looking at
   * 
   */
  private void consume(DotNode dotNode, int l) {
    /*
     * 1. If the trie node has any rules, we can add them to the collection
     * 
     * 2. Next, look at all the outgoing nonterminal arcs of the trie node. If any of them
     * match an existing chart item, then we know we can extend (i,j) to (i,l). We then
     * recurse for all m from l+1 to n (the end of the sentence)
     * 
     * 3. We also try to match terminals if (j + 1 == l)
     */
    
//    System.err.println(String.format("CONSUME %s / %d %d %d", dotNode, dotNode.begin(), dotNode.end(), l));
    
    // The span that's already been consumed
    int i = dotNode.begin();
    int j = dotNode.end();
    Trie trie = dotNode.getTrieNode();
    
    // Try to match terminals
    if (inputLattice.distance(j, l) == 1) {
      // Get the current sentence node, and explore all outgoing arcs, since we might be decoding
      // a lattice. For sentence decoding, this is trivial: there is only one outgoing arc.
      Node<Integer> inputNode = sentence.getNode(j);
      for (Arc<Integer> arc : inputNode.getOutgoingArcs()) {
        int word = arc.getLabel();
        Trie nextTrie;
        if ((nextTrie = trie.match(word)) != null) {
          // add to chart item over (i, l)
          addToChart(dotNode.extend(arc, nextTrie), i == j);
        }
      }
    }
        
    // Now try to match nonterminals
    if (trie.hasExtensions()) {
      // Get a list of all the supernodes, and query each to see if it's in the grammar
      if (cells.get(j, l) != null) {
        Map<Integer,SuperNode> items = getCell(j,l).getSortedSuperItems();
        for (int lhs: items.keySet()) {
          Trie nextTrie = trie.match(lhs);
          if (nextTrie != null) {
            // add item over (i, l) to candidates list
            addToChart(dotNode.extend(items.get(lhs), nextTrie), i == j);
          }
        }
      }
    }
  }
  
  /**
   * Record the completed rule with backpointers for later cube-pruning.
   * 
   * @param width
   * @param rules
   * @param tailNodes
   */
  private void addToCandidates(DotNode dotNode) {
//    System.err.println(String.format("ADD TO CANDIDATES %s AT INDEX %d", dotNode, dotNode.end() - dotNode.begin()));
    
    // TODO: one entry per rule, or per rule instantiation (rule together with unique matching of input)?
    List<Rule> rules = dotNode.getRuleCollection().getSortedRules(featureFunctions); 
    Rule bestRule = rules.get(0);
    List<SuperNode> superNodes = dotNode.getAntSuperNodes();
    
    List<HGNode> tailNodes = new ArrayList<HGNode>();
    for (SuperNode superNode : superNodes)
      tailNodes.add(superNode.nodes.get(0));

    int[] ranks = new int[1 + superNodes.size()];
    Arrays.fill(ranks, 1);
    
    ComputeNodeResult result = new ComputeNodeResult(featureFunctions, bestRule, tailNodes, 
        dotNode.begin(), dotNode.end(), dotNode.getSourcePath(), sentence);
    CubePruneState seedState = new CubePruneState(result, ranks, rules, tailNodes, dotNode);
    
    allCandidates[dotNode.end() - dotNode.begin()].add(seedState);
  }
  
  /**
   * Adds all rules at a trie node to the chart, unless its a unary rule. A unary rule is the
   * first outgoing arc of a grammar's root trie. For terminals, these are added during the
   * seeding stage; for nonterminals, these confuse cube pruning and can result in infinite loops, 
   * and are handled separately (see addUnaryNodes());
   *  
   * @param trie the grammar node
   * @param isUnary whether the rules at this dotnode are unary
   */
  private void addToChart(DotNode dotNode, boolean isUnary) {
    
//    System.err.println(String.format("ADD TO CHART %s unary=%s", dotNode, isUnary));

    if (! isUnary && dotNode.hasRules())
      addToCandidates(dotNode);

    int j = dotNode.end();
    for (int l = j + 1; l <= sentence.length(); l++)
      consume(dotNode, l);
  }

  
  /**
   * This function performs the main work of decoding.
   * 
   * @return the hypergraph containing the translated sentence.
   */
  public HyperGraph expand() {

    for (int width = 1; width <= sourceLength; width++) {
      for (int i = 0; i <= sourceLength - width; i++) {
        int j = i + width;
        if (logger.isLoggable(Level.FINEST))
          logger.finest(String.format("Processing span (%d, %d)", i, j));

        /* Skips spans for which no path exists (possible in lattices). */
        if (inputLattice.distance(i, j) == Float.POSITIVE_INFINITY) {
          continue;
        }

        /*
         * 1. Expand the dot through all rules. This is a matter of (a) look for
         * rules over (i,j-1) that need the terminal at (j-1,j) and looking at
         * all split points k to expand nonterminals.
         */
        logger.finest("Expanding cell");
        for (int k = 0; k < this.grammars.length; k++) {
          /**
           * Each dotChart can act individually (without consulting other
           * dotCharts) because it either consumes the source input or the
           * complete nonTerminals, which are both grammar-independent.
           **/
          this.dotcharts[k].expandDotCell(i, j);
        }

        /*
         * 2. The regular CKY part: add completed items onto the chart via cube
         * pruning.
         */
        logger.finest("Adding complete items into chart");
        completeSpan(i, j);

        /* 3. Process unary rules. */
        logger.finest("Adding unary items into chart");
        addUnaryNodes(this.grammars, i, j);

        // (4)=== in dot_cell(i,j), add dot-nodes that start from the /complete/
        // superIterms in
        // chart_cell(i,j)
        logger.finest("Initializing new dot-items that start from complete items in this cell");
        for (int k = 0; k < this.grammars.length; k++) {
          if (this.grammars[k].hasRuleForSpan(i, j, inputLattice.distance(i, j))) {
            this.dotcharts[k].startDotItems(i, j);
          }
        }

        /*
         * 5. Sort the nodes in the cell.
         * 
         * Sort the nodes in this span, to make them usable for future
         * applications of cube pruning.
         */
        if (null != this.cells.get(i, j)) {
          this.cells.get(i, j).getSortedNodes();
        }
      }
    }

    logStatistics(Level.INFO);

    // transition_final: setup a goal item, which may have many deductions
    if (null == this.cells.get(0, sourceLength)
        || !this.goalBin.transitToGoal(this.cells.get(0, sourceLength), this.featureFunctions,
            this.sourceLength)) {
      logger.severe("No complete item in the Cell[0," + sourceLength + "]; possible reasons: "
          + "(1) your grammar does not have any valid derivation for the source sentence; "
          + "(2) too aggressive pruning.");
      return null;
    }

    logger.fine("Finished expand");
    return new HyperGraph(this.goalBin.getSortedNodes().get(0), -1, -1, this.sentence);
  }

  /**
   * Get the requested cell, creating the entry if it doesn't already exist.
   * 
   * @param i span start
   * @param j span end
   * @return the cell item
   */
  public Cell getCell(int i, int j) {
    assert i >= 0;
    assert i <= sentence.length();
    assert i <= j;
    if (cells.get(i, j) == null)
      cells.set(i, j, new Cell(this, goalSymbolID));

    return cells.get(i, j);
  }

  // ===============================================================
  // Private methods
  // ===============================================================

  private void logStatistics(Level level) {
    Decoder.LOG(2, 
        String.format("Input %d: Chart: added %d merged %d dot-items added: %d",
            this.sentence.id(), this.nAdded, this.nMerged, this.nDotitemAdded));
  }

  /**
   * Handles expansion of unary rules. Rules are expanded in an agenda-based
   * manner to avoid constructing infinite unary chains. Assumes a triangle
   * inequality of unary rule expansion (e.g., A -> B will always be cheaper
   * than A -> C -> B), which is not a true assumption.
   * 
   * @param grammars A list of the grammars for the sentence
   * @param i
   * @param j
   * @return the number of nodes added
   */
  private int addUnaryNodes(Grammar[] grammars, int i, int j) {

    Cell chartBin = this.cells.get(i, j);
    if (null == chartBin) {
      return 0;
    }
    int qtyAdditionsToQueue = 0;
    ArrayList<HGNode> queue = new ArrayList<HGNode>(chartBin.getSortedNodes());
    HashSet<Integer> seen_lhs = new HashSet<Integer>();

    if (logger.isLoggable(Level.FINEST))
      logger.finest("Adding unary to [" + i + ", " + j + "]");

    while (queue.size() > 0) {
      HGNode node = queue.remove(0);
      seen_lhs.add(node.lhs);

      for (Grammar gr : grammars) {
        if (!gr.hasRuleForSpan(i, j, inputLattice.distance(i, j)))
          continue;

        /*
         * Match against the node's LHS, and then make sure the rule collection
         * has unary rules
         */
        Trie childNode = gr.getTrieRoot().match(node.lhs);
        if (childNode != null && childNode.getRuleCollection() != null
            && childNode.getRuleCollection().getArity() == 1) {

          ArrayList<HGNode> antecedents = new ArrayList<HGNode>();
          antecedents.add(node);

          List<Rule> rules = childNode.getRuleCollection().getSortedRules(this.featureFunctions);
          for (Rule rule : rules) { // for each unary rules

/*          
            // DON'T USE UNIQUE STATE CHECKING!!!  
            HyperEdge newEdge = new HyperEdge(rule, 0.0f, 0.0f, antecedents, new SourcePath());
            HGNode newNode = new HGNode(i, j, rule.getLHS(), null, newEdge, 0.0f);
            DerivationState state = this.kBestExtractor.new DerivationState(newNode, newEdge, null, 0.0f, -1);
            
            ComputeNodeResult result = new ComputeNodeResult(this.featureFunctions, state, i, j,
                new SourcePath(), this.sentence);
            HGNode resNode = chartBin.addHyperEdgeInCell(result, rule, i, j, antecedents,
                new SourcePath(), true);
*/
            
            ComputeNodeResult states = new ComputeNodeResult(this.featureFunctions, rule,
                antecedents, i, j, new SourcePath(), this.sentence);
            HGNode resNode = chartBin.addHyperEdgeInCell(states, rule, i, j, antecedents,
                new SourcePath(), true);


            if (logger.isLoggable(Level.FINEST))
              logger.finest(rule.toString());

            if (null != resNode && !seen_lhs.contains(resNode.lhs)) {
              queue.add(resNode);
              qtyAdditionsToQueue++;
            }
          }
        }
      }
    }
    return qtyAdditionsToQueue;
  }

  /***
   * Add a terminal production (X -> english phrase) to the hypergraph.
   * 
   * @param i the start index
   * @param j stop index
   * @param rule the terminal rule applied
   * @param srcPath the source path cost
   */
  public void addAxiom(int i, int j, Rule rule, SourcePath srcPath) {
    if (null == this.cells.get(i, j)) {
      this.cells.set(i, j, new Cell(this, this.goalSymbolID));
    }

/*
    // DON'T USE UNIQUE STATE CHECKING!!!
    HyperEdge newEdge = new HyperEdge(rule, 0.0f, 0.0f, null, new SourcePath());
    HGNode newNode = new HGNode(i, j, rule.getLHS(), null, newEdge, 0.0f);
    DerivationState state = this.kBestExtractor.new DerivationState(newNode, newEdge, null, 0.0f, -1);
    
    this.cells.get(i, j)
        .addHyperEdgeInCell(
            new ComputeNodeResult(this.featureFunctions, state, i, j, srcPath,
                sentence), rule, i, j, null, srcPath, false);
*/
    
    this.cells.get(i, j).addHyperEdgeInCell(
        new ComputeNodeResult(this.featureFunctions, rule, null, i, j, srcPath, sentence), rule,
        i, j, null, srcPath, false);

  }
}
