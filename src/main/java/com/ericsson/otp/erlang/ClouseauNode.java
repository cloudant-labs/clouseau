package com.ericsson.otp.erlang;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import com.cloudant.cloujeau.Service;
import com.cloudant.cloujeau.ServiceRegistry;

public class ClouseauNode extends OtpNode {

    private final ExecutorService executor;
    private final ServiceRegistry serviceRegistry;

    public ClouseauNode(String node, String cookie, final ExecutorService executor,
            final ServiceRegistry serviceRegistry) throws IOException {
        super(node, cookie);
        this.executor = executor;
        this.serviceRegistry = serviceRegistry;
    }

    @Override
    boolean deliver(OtpMsg m) {
        final boolean delivered = super.deliver(m);
        if (delivered) {
            final int t = m.type();
            if (t == OtpMsg.regSendTag) {
                final String name = m.getRecipientName();
                if (!name.equals("net_kernel")) {
                    final Service service = serviceRegistry.lookup(m.getRecipientName());
                    // executor.execute(service);
                }
            } else {
                final Service service = serviceRegistry.lookup(m.getRecipientPid());
                // executor.execute(service);
            }
        }
        return delivered;
    }

}
