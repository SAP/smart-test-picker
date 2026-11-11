// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.coverage.model.ExecutableTestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import java.util.Objects;
import java.util.Set;

/** Producer-supplied ownership evidence; diagnostic text never determines severity. */
public record CollectorError(Classification classification, String message,
        Set<ExecutableTestIdentity> affectedIdentities, MethodIdentity site) {
    public enum Classification { SHARD_FATAL, IDENTITY_LOCAL, SITE_LOCAL }

    public CollectorError {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(message, "message");
        affectedIdentities = Set.copyOf(affectedIdentities);
        if (classification == Classification.IDENTITY_LOCAL && affectedIdentities.isEmpty())
            throw new IllegalArgumentException("Identity-local errors require explicit affected identities");
        if (classification == Classification.SITE_LOCAL && site == null)
            throw new IllegalArgumentException("Site-local errors require an explicit method identity");
    }

    public static CollectorError fatal(String message) {
        return new CollectorError(Classification.SHARD_FATAL, message, Set.of(), null);
    }

    public static CollectorError local(String message, ExecutableTestIdentity identity) {
        return new CollectorError(Classification.IDENTITY_LOCAL, message, Set.of(identity), null);
    }

    public static CollectorError site(String message, MethodIdentity site) {
        return new CollectorError(Classification.SITE_LOCAL, message, Set.of(), site);
    }

    public boolean shardFatal() {
        return classification == Classification.SHARD_FATAL
                || classification == Classification.SITE_LOCAL && affectedIdentities.isEmpty();
    }
}
