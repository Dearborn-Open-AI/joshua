
SRC        =src/
EXAMPLE    =../example/example
JAVA_FLAGS =


.SUFFIXES:
.SUFFIXES: .java .class

.PHONY: all joshua test srilm_inter clean

all:
	@$(MAKE) clean
	@$(MAKE) joshua
	@$(MAKE) test

joshua:
	( cd $(SRC) && \
		find -X edu/jhu/joshua/decoder/ -name '*.java' \
		| xargs javac $(JAVA_FLAGS) )

test:
	( cd $(SRC) && \
		java -Xmx2000m -Xms2000m       \
		edu.jhu.joshua.decoder.Decoder \
		$(EXAMPLE).config.javalm       \
		$(EXAMPLE).test.in             \
		$(EXAMPLE).nbest.javalm.out    \
		2>&1 | tee $(EXAMPLE).nbest.javalm.err )

srilm_inter:
	( cd $(SRC)edu/jhu/ckyDecoder && make )

clean:
	find ./$(SRC) -name '*.class' -exec rm {} \;
