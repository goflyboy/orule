package com.orule.rule.execution.execution.java;

/**
 * Legacy hard-coded Customer/Order demo domain prefix.
 *
 * <p>RFC-0045 v0.2 (s1a) retired the {@code CUSTOMER_ORDER} literal and the
 * {@link #apply(String)} helper. Production path now goes through
 * {@link DomainTypePrefixGenerator#render(java.util.List)}.
 *
 * <p>This class is kept as an empty shell for v0.2 to avoid breaking any
 * stray references; it will be deleted in the v0.3 cleanup PR.
 *
 * @deprecated since RFC-0045 v0.2; will be removed in v0.3. Use
 *             {@link DomainTypePrefixGenerator} instead.
 */
@Deprecated(forRemoval = true, since = "RFC-0045-v0.2")
public final class DomainTypePrefix {

    private DomainTypePrefix() {}
}
