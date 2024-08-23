/*
 * %CopyrightBegin%
 *
 * Copyright IBM Corp. 2024. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * %CopyrightEnd%
 */
package com.ericsson.otp.erlang;

// package scope
class Monitor {
    private final OtpErlangPid local;
    private final OtpErlangPid remotePid;
    private final OtpErlangAtom remoteName;
    private final OtpErlangRef ref;
    private final String node;

    private int hashCodeValue = 0;

    public Monitor(final OtpErlangPid local, final OtpErlangPid remote, final OtpErlangRef ref) {
        this.local = local;
        this.remotePid = remote;
        this.remoteName = null;
        this.ref = ref;
        this.node = remote.node();
    }

    public Monitor(final OtpErlangPid local, final OtpErlangAtom remote, final String node, final OtpErlangRef ref) {
        this.local = local;
        this.remotePid = null;
        this.remoteName = remote;
        this.ref = ref;
        this.node = node;
    }

    public OtpErlangPid local() {
        return local;
    }

    public boolean isNamed() {
        return remoteName != null;
    }

    public OtpErlangObject remote() {
        if (remotePid == null) {
            return remoteName;
        } else {
            return remotePid;
        }
    }

    public OtpErlangRef ref() {
        return ref;
    }

    public String node() {
        return node;
    }

    public boolean equals(final Monitor other) {
        return ref.equals(other.ref) && local.equals(other.local) && (
            remotePid.equals(other.remotePid) || remoteName.equals(other.remoteName)
        );
    }

    @Override
    public int hashCode() {
        if (hashCodeValue == 0) {
            final OtpErlangObject.Hash hash = new OtpErlangObject.Hash(5);
            final int remoteHashCode;
            if (remotePid == null) {
                remoteHashCode = remoteName.hashCode();
            } else {
                remoteHashCode = remotePid.hashCode();
            }
            hash.combine(local.hashCode() + remoteHashCode + ref.hashCode());
            hashCodeValue = hash.valueOf();
        }
        return hashCodeValue;
    }
}
