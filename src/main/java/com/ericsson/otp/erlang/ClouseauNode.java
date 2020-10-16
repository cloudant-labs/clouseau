package com.ericsson.otp.erlang;

import java.io.IOException;

import com.cloudant.clouseau.ServiceRegistry;

public class ClouseauNode extends OtpNode {

    private final ServiceRegistry serviceRegistry;

    public ClouseauNode(String node, String cookie, final ServiceRegistry serviceRegistry) throws IOException {
        super(node, cookie);
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
                    serviceRegistry.addPending(m.getRecipientName());

                }
            } else {
                serviceRegistry.addPending(m.getRecipientPid());
            }
        }
        return delivered;
    }

}
