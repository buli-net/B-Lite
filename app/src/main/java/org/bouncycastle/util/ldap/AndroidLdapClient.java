package org.bouncycastle.util.ldap;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.util.ssl.SSLUtil;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Android-compatible LDAP access used by the Bouncy Castle LDAP store adapters. */
public final class AndroidLdapClient {
    private AndroidLdapClient() { }

    /** Returns all values of the requested attributes from matching LDAP entries. */
    public static List<Object> search(String ldapUrl, String baseDn, String filter, String[] attributes)
            throws Exception {
        URI uri = new URI(ldapUrl);
        String scheme = uri.getScheme();
        boolean ssl = scheme != null && scheme.equalsIgnoreCase("ldaps");
        String host = uri.getHost();
        if (host == null || host.length() == 0) {
            throw new IllegalArgumentException("LDAP URL must specify a host: " + ldapUrl);
        }
        int port = uri.getPort();
        if (port < 0) port = ssl ? 636 : 389;
        String effectiveBase = baseDn;
        if (effectiveBase == null || effectiveBase.length() == 0) {
            String path = uri.getRawPath();
            if (path != null && path.length() > 1) {
                effectiveBase = URLDecoder.decode(path.substring(1), StandardCharsets.UTF_8.name());
            } else {
                effectiveBase = "";
            }
        }
        LDAPConnection connection = null;
        try {
            if (ssl) {
                connection = new LDAPConnection(new SSLUtil().createSSLSocketFactory(), host, port);
            } else {
                connection = new LDAPConnection(host, port);
            }
            SearchRequest request = new SearchRequest(effectiveBase, SearchScope.SUB, filter, attributes);
            SearchResult result = connection.search(request);
            List<Object> values = new ArrayList<Object>();
            for (SearchResultEntry entry : result.getSearchEntries()) {
                for (String attributeName : attributes) {
                    Attribute attribute = entry.getAttribute(attributeName);
                    if (attribute != null) {
                        for (byte[] value : attribute.getValueByteArrays()) {
                            values.add(value);
                        }
                    }
                }
            }
            return values;
        } finally {
            if (connection != null && connection.isConnected()) {
                connection.close();
            }
        }
    }

    /** Reads the CRL attribute from an LDAP distribution-point URL. */
    public static List<Object> readCrl(String ldapUrl) throws Exception {
        URI uri = new URI(ldapUrl);
        String path = uri.getRawPath();
        String baseDn = "";
        if (path != null && path.length() > 1) {
            baseDn = URLDecoder.decode(path.substring(1), StandardCharsets.UTF_8.name());
        }
        return search(ldapUrl, baseDn, "(objectClass=*)", new String[] { "certificateRevocationList;binary" });
    }
}
