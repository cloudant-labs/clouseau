You will need to create a file ~/.m2/toolchains.xml to build. Below is
an example, you will likely only need to adjust the jdkHome line.

<?xml version="1.0" encoding="UTF8"?>
<toolchains>
  <!-- JDK toolchains -->
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>1.6</version>
      <vendor>sun</vendor>
    </provides>
    <configuration>
      <jdkHome>/Library/Java/JavaVirtualMachines/1.6.0.jdk/Contents/Home</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
