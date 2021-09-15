.PHONY: build setup test

test:
	mvn test

build:
	mvn scala:compile

setup:
	mvn install:install-file \
      -Dfile=./deps/OtpErlang.jar \
      -DgroupId=org.erlang.otp\
      -DartifactId=jinterface \
      -Dversion=1.12 \
      -Dpackaging=jar \
      -DgeneratePom=true
