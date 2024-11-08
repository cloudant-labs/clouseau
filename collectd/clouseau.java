import java.net.*;
import java.util.*;
import javax.management.*;
import javax.management.remote.*;

public class clouseau {

    private static String host;
    private static int interval;
    private static MBeanServerConnection conn;

    public static void main(String[] args) throws Exception {
        host = InetAddress.getLocalHost().getCanonicalHostName();
        conn = connect(args);

        getNames("\"com.cloudant.clouseau\":name=\"searches\",*");
        getNames("\"com.cloudant.clouseau\":name=\"opens\",*");
        getNames("\"com.cloudant.clouseau\":name=\"commits\",*");
        getNames("\"com.cloudant.clouseau\":name=\"updates\",*");
        getNames("\"com.cloudant.clouseau\":name=\"deletes\",*");
        getNames("\"com.cloudant.clouseau\":type=\"IndexManagerService\",name=\"lru.*\",*");
        getNames("\"com.cloudant.clouseau\":type=\"IndexService\",name=\"partition_search.timeout.count\",*");
    }

    @SuppressWarnings(value = "unchecked")
    private static MBeanServerConnection connect(final String[] args) throws Exception {
        final Map env = new HashMap();
        String[] creds = {args[1], args[2]};
        env.put(JMXConnector.CREDENTIALS, creds);
        JMXServiceURL serviceURL = new JMXServiceURL(args[0]);
        JMXConnector connector = JMXConnectorFactory.connect(serviceURL, env);
        return connector.getMBeanServerConnection();
    }

    private static void getNames(String path) throws Exception {
        final Set<ObjectName> names = conn.queryNames(new ObjectName(path), null);

        for (final ObjectName name : names) {
            System.out.println(name.getCanonicalName());
        }
    }

}
