package com.cloudant.ziose.scalang

import com.cloudant.ziose.macros.CheckEnv
import com.codahale.metrics.jmx.ObjectNameFactory
import javax.management.ObjectName
import javax.management.MalformedObjectNameException

case class JmxObjectNameComponents(domain: String, packageName: String, service: String, name: String) {
  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"domain=$domain",
    s"packageName=$packageName",
    s"service=$service",
    s"name=$name"
  )
}

class JmxObjectNameFactory(
  transformer: (JmxObjectNameComponents) => JmxObjectNameComponents
) extends ObjectNameFactory {
  /*
    Receives name as "com.cloudant.ziose.clouseau.InitService|TZTimerHistogram.le.1"
    split it into components

    suffix(optional) = le.1
    metricName = TZTimerHistogram
    serviceName = InitService
    packageName = com.cloudant.ziose.clouseau

    Then it calls transformer to massage components as needed
   */
  override def createName(`type`: String, domain: String, name: String): ObjectName = {
    try {
      // For example: `com.cloudant.ziose.clouseau.InitService|TZTimerHistogram`
      val parts = name.split("\\|")
      if (parts.length != 2) throw new RuntimeException("JMX name must include single '|'")
      // For example: `com.cloudant.ziose.clouseau.InitService`
      val path = parts(0)
      // For example: `TZTimerHistogram.le.1`
      val metricName = parts(1)

      val serviceNameBegin = path.lastIndexOf(".")
      if (serviceNameBegin < 1) throw new RuntimeException("JMX service name must include package name")
      // For example: `InitService`
      val serviceName = path.substring(serviceNameBegin + 1)
      if (serviceName.length == 0) throw new RuntimeException("JMX service name must include class name")
      // For example: `com.cloudant.ziose.clouseau`
      val packageName = path.substring(0, serviceNameBegin)
      if (packageName.length == 0) throw new RuntimeException("JMX service package name shouldn't be empty")

      val parsedComponents = JmxObjectNameComponents(domain, packageName, serviceName, metricName)
      val components       = transformer(parsedComponents)
      if (components.domain.isEmpty) throw new RuntimeException("JMX name transformer must return valid domain")
      if (components.service.isEmpty) throw new RuntimeException("JMX name transformer must return valid service")
      if (components.name.isEmpty) throw new RuntimeException("JMX name transformer must return valid name")

      val metricNameBuilder = new StringBuilder(ObjectName.quote(components.domain))
      metricNameBuilder.append(':')
      metricNameBuilder.append("type=")
      metricNameBuilder.append(ObjectName.quote(components.service))
      metricNameBuilder.append(',')
      metricNameBuilder.append("name=")
      metricNameBuilder.append(ObjectName.quote(components.name))

      new ObjectName(metricNameBuilder.toString)
    } catch {
      case e: MalformedObjectNameException =>
        try {
          new ObjectName(domain, "name", ObjectName.quote(name))
        } catch {
          case e1: MalformedObjectNameException =>
            throw new RuntimeException(e1)
        }
    }
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"transformer=$transformer"
  )
}
