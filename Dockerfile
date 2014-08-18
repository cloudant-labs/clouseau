FROM ubuntu:12.04
MAINTAINER Robert Newson <robert.newson@uk.ibm.com>
ENV DEBIAN_FRONTEND noninteractive

# Install prereqs
RUN apt-get -qq update
RUN apt-get -y install git openjdk-6-jdk maven

# Run test suite
RUN find / -name pom.xml
RUN ls -al
RUN mvn test
