package com.ericsson.otp.erlang;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ClouseauNode extends OtpNode {

    private final Set<Object> active = Collections.synchronizedSet(new HashSet<Object>());

    public ClouseauNode(String node, OtpTransportFactory transportFactory) throws IOException {
        super(node, transportFactory);
    }

    public ClouseauNode(String node, String cookie, int port, OtpTransportFactory transportFactory) throws IOException {
        super(node, cookie, port, transportFactory);
    }

    public ClouseauNode(String node, String cookie, int port) throws IOException {
        super(node, cookie, port);
    }

    public ClouseauNode(String node, String cookie, OtpTransportFactory transportFactory) throws IOException {
        super(node, cookie, transportFactory);
    }

    public ClouseauNode(String node, String cookie) throws IOException {
        super(node, cookie);
    }

    public ClouseauNode(String node) throws IOException {
        super(node);
    }

    @Override
    boolean deliver(OtpMsg m) {
        final boolean delivered = super.deliver(m);
        if (delivered) {
            final int t = m.type();
            if (t == OtpMsg.regSendTag) {
                final String name = m.getRecipientName();
                if (!name.equals("net_kernel")) {
                    active.add(m.getRecipientName());
                }
            } else {
                active.add(m.getRecipientPid());
            }
        }
        return delivered;
    }

}
