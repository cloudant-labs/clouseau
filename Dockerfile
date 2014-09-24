FROM ubuntu:12.04
MAINTAINER Robert Newson <rnewson@apache.org>
ENV DEBIAN_FRONTEND noninteractive

RUN apt-get -qq update
RUN apt-get -y --no-install-recommends install erlang-nox openjdk-6-jdk maven

RUN useradd -m clouseau
USER clouseau
WORKDIR /home/clouseau

ADD . /home/clouseau/

RUN mvn test
