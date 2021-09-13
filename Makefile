.PHONY: build setup


build:
	mvn scala:compile

setup:
	mvn install:install-file \
      -Dfile=./deps/jinterface-1.11.jar \
      -DgroupId=org.erlang.otp\
      -DartifactId=jinterface \
      -Dversion=1.11 \
      -Dpackaging=jar \
      -DgeneratePom=true
