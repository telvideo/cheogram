package eu.siacs.conversations.utils;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.net.InetAddresses;
import com.google.common.primitives.Ints;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

//import de.gultsch.minidns.AndroidDNSClient;
import org.minidns.AbstractDnsClient;
import org.minidns.DnsCache;
import org.minidns.DnsClient;
import org.minidns.cache.LruCache;
import org.minidns.dnsmessage.Question;
import org.minidns.dnsname.DnsName;
import org.minidns.dnssec.DnssecResultNotAuthenticException;
import org.minidns.dnssec.DnssecValidationFailedException;
import org.minidns.dnsserverlookup.AndroidUsingExec;
import org.minidns.hla.DnssecResolverApi;
import org.minidns.hla.ResolverApi;
import org.minidns.hla.ResolverResult;
import org.minidns.iterative.ReliableDnsClient;
import org.minidns.record.A;
import org.minidns.record.AAAA;
import org.minidns.record.CNAME;
import org.minidns.record.Data;
import org.minidns.record.InternetAddressRR;
import org.minidns.record.Record;
import org.minidns.record.SRV;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;

public class Resolver {

    public static final int DEFAULT_PORT_XMPP = 5222;

    private static final String DIRECT_TLS_SERVICE = "_xmpps-client";
    private static final String STARTTLS_SERVICE = "_xmpp-client";

    private static XmppConnectionService SERVICE = null;

    private static List<String> DNSSECLESS_TLDS = Arrays.asList(
        "ae",
        "aero",
        "ai",
        "al",
        "ao",
        "aq",
        "as",
        "ba",
        "bb",
        "bd",
        "bf",
        "bi",
        "bj",
        "bn",
        "bo",
        "bs",
        "bw",
        "cd",
        "cf",
        "cg",
        "ci",
        "ck",
        "cm",
        "cu",
        "cv",
        "cw",
        "dj",
        "dm",
        "do",
        "ec",
        "eg",
        "eh",
        "er",
        "et",
        "fj",
        "fk",
        "ga",
        "ge",
        "gf",
        "gh",
        "gm",
        "gp",
        "gq",
        "gt",
        "gu",
        "hm",
        "ht",
        "im",
        "ir",
        "je",
        "jm",
        "jo",
        "ke",
        "kh",
        "km",
        "kn",
        "kp",
        "kz",
        "ls",
        "mg",
        "mh",
        "mk",
        "ml",
        "mm",
        "mo",
        "mp",
        "mq",
        "ms",
        "mt",
        "mu",
        "mv",
        "mw",
        "mz",
        "ne",
        "ng",
        "ni",
        "np",
        "nr",
        "om",
        "pa",
        "pf",
        "pg",
        "pk",
        "pn",
        "ps",
        "py",
        "qa",
        "rw",
        "sd",
        "sl",
        "sm",
        "so",
        "sr",
        "sv",
        "sy",
        "sz",
        "tc",
        "td",
        "tg",
        "tj",
        "to",
        "tr",
        "va",
        "vg",
        "vi",
        "ye",
        "zm",
        "zw"
    );

    public static void init(XmppConnectionService service) {
        Resolver.SERVICE = service;
        DnsClient.removeDNSServerLookupMechanism(AndroidUsingExec.INSTANCE);
        DnsClient.addDnsServerLookupMechanism(AndroidUsingExecLowPriority.INSTANCE);
        DnsClient.addDnsServerLookupMechanism(new AndroidUsingLinkProperties(service));
        final AbstractDnsClient client = ResolverApi.INSTANCE.getClient();
        if (client instanceof ReliableDnsClient) {
            ((ReliableDnsClient) client).setUseHardcodedDnsServers(false);
        }
    }

    public static List<Result> fromHardCoded(final String hostname, final int port) {
        final Result result = new Result();
        result.hostname = DnsName.from(hostname);
        result.port = port;
        result.directTls = useDirectTls(port);
        result.authenticated = true;
        return Collections.singletonList(result);
    }

    public static void checkDomain(final Jid jid) {
        DnsName.from(jid.getDomain());
    }

    public static boolean invalidHostname(final String hostname) {
        try {
            DnsName.from(hostname);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    public static void clearCache() {
        final AbstractDnsClient client = ResolverApi.INSTANCE.getClient();
        final DnsCache dnsCache = client.getCache();
        if (dnsCache instanceof LruCache) {
            Log.d(Config.LOGTAG,"clearing DNS cache");
            ((LruCache) dnsCache).clear();
        }

        final AbstractDnsClient clientSec = DnssecResolverApi.INSTANCE.getClient();
        final DnsCache dnsCacheSec = clientSec.getCache();
        if (dnsCacheSec instanceof LruCache) {
            Log.d(Config.LOGTAG,"clearing DNSSEC cache");
            ((LruCache) dnsCacheSec).clear();
        }
    }


    public static boolean useDirectTls(final int port) {
        return port == 443 || port == 5223;
    }

    public static List<Result> resolve(final String domain) {
        final  List<Result> ipResults = fromIpAddress(domain);
        if (ipResults.size() > 0) {
            return ipResults;
        }
        final List<Result> results = new ArrayList<>();
        final List<Result> fallbackResults = new ArrayList<>();
        final Thread[] threads = new Thread[3];
        threads[0] = new Thread(() -> {
            try {
                final List<Result> list = resolveSrv(domain, true);
                synchronized (results) {
                    results.addAll(list);
                }
            } catch (final Throwable throwable) {
                if (!(Throwables.getRootCause(throwable) instanceof InterruptedException)) {
                    Log.d(Config.LOGTAG, Resolver.class.getSimpleName() + ": error resolving SRV record (direct TLS)", throwable);
                }
            }
        });
        threads[1] = new Thread(() -> {
            try {
                final List<Result> list = resolveSrv(domain, false);
                synchronized (results) {
                    results.addAll(list);
                }
            } catch (final Throwable throwable) {
                if (!(Throwables.getRootCause(throwable) instanceof InterruptedException)) {
                    Log.d(Config.LOGTAG, Resolver.class.getSimpleName() + ": error resolving SRV record (STARTTLS)", throwable);
                }
            }
        });
        threads[2] = new Thread(() -> {
            List<Result> list = resolveNoSrvRecords(DnsName.from(domain), true);
            synchronized (fallbackResults) {
                fallbackResults.addAll(list);
            }
        });
        for (final Thread thread : threads) {
            thread.start();
        }
        try {
            threads[0].join();
            threads[1].join();
            if (results.size() > 0) {
                threads[2].interrupt();
                synchronized (results) {
                    Collections.sort(results);
                    Log.d(Config.LOGTAG, Resolver.class.getSimpleName() + ": " + results.toString());
                    return new ArrayList<>(results);
                }
            } else {
                threads[2].join();
                synchronized (fallbackResults) {
                    Collections.sort(fallbackResults);
                    Log.d(Config.LOGTAG, Resolver.class.getSimpleName() + ": " + fallbackResults.toString());
                    return new ArrayList<>(fallbackResults);
                }
            }
        } catch (InterruptedException e) {
            for (Thread thread : threads) {
                thread.interrupt();
            }
            return Collections.emptyList();
        }
    }

    private static List<Result> fromIpAddress(String domain) {
        if (!IP.matches(domain)) {
            return Collections.emptyList();
        }
        try {
            Result result = new Result();
            result.ip = InetAddress.getByName(domain);
            result.port = DEFAULT_PORT_XMPP;
            result.authenticated = true;
            return Collections.singletonList(result);
        } catch (UnknownHostException e) {
            return Collections.emptyList();
        }
    }

    private static List<Result> resolveSrv(String domain, final boolean directTls) throws IOException {
        DnsName dnsName = DnsName.from((directTls ? DIRECT_TLS_SERVICE : STARTTLS_SERVICE) + "._tcp." + domain);
        ResolverResult<SRV> result = resolveWithFallback(dnsName, SRV.class);
        final List<Result> results = new ArrayList<>();
        final List<Thread> threads = new ArrayList<>();
        for (SRV record : result.getAnswersOrEmptySet()) {
            if (record.name.length() == 0 && record.priority == 0) {
                continue;
            }
            threads.add(new Thread(() -> {
                final List<Result> ipv4s = resolveIp(record, A.class, result.isAuthenticData(), directTls);
                if (ipv4s.size() == 0) {
                    Result resolverResult = Result.fromRecord(record, directTls);
                    resolverResult.authenticated = result.isAuthenticData();
                    ipv4s.add(resolverResult);
                }
                synchronized (results) {
                    results.addAll(ipv4s);
                }

            }));
            threads.add(new Thread(() -> {
                final List<Result> ipv6s = resolveIp(record, AAAA.class, result.isAuthenticData(), directTls);
                synchronized (results) {
                    results.addAll(ipv6s);
                }
            }));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                return Collections.emptyList();
            }
        }
        return results;
    }

    private static <D extends InternetAddressRR> List<Result> resolveIp(SRV srv, Class<D> type, boolean authenticated, boolean directTls) {
        List<Result> list = new ArrayList<>();
        try {
            ResolverResult<D> results = resolveWithFallback(srv.name, type);
            for (D record : results.getAnswersOrEmptySet()) {
                Result resolverResult = Result.fromRecord(srv, directTls);
                resolverResult.authenticated = results.isAuthenticData() && authenticated;
                resolverResult.ip = record.getInetAddress();
                list.add(resolverResult);
            }
        } catch (Throwable t) {
            Log.d(Config.LOGTAG, Resolver.class.getSimpleName() + ": error resolving " + type.getSimpleName() + " " + t.getMessage());
        }
        return list;
    }

    private static List<Result> resolveNoSrvRecords(DnsName dnsName, boolean withCnames) {
        List<Result> results = new ArrayList<>();
        try {
            ResolverResult<A> aResult = resolveWithFallback(dnsName, A.class);
            for (A a : aResult.getAnswersOrEmptySet()) {
                Result r = Result.createDefault(dnsName, a.getInetAddress());
                r.authenticated = aResult.isAuthenticData();
                results.add(r);
            }
            ResolverResult<AAAA> aaaaResult = resolveWithFallback(dnsName, AAAA.class);
            for (AAAA aaaa : aaaaResult.getAnswersOrEmptySet()) {
                Result r = Result.createDefault(dnsName, aaaa.getInetAddress());
                r.authenticated = aaaaResult.isAuthenticData();
                results.add(r);
            }
            if (results.size() == 0 && withCnames) {
                ResolverResult<CNAME> cnameResult = resolveWithFallback(dnsName, CNAME.class);
                for (CNAME cname : cnameResult.getAnswersOrEmptySet()) {
                    for (Result r : resolveNoSrvRecords(cname.name, false)) {
                        r.authenticated = r.authenticated && cnameResult.isAuthenticData();
                        results.add(r);
                    }
                }
            }
        } catch (final Throwable throwable) {
            if (!(Throwables.getRootCause(throwable) instanceof InterruptedException)) {
                Log.d(Config.LOGTAG, Resolver.class.getSimpleName() + "error resolving fallback records", throwable);
            }
        }
        results.add(Result.createDefault(dnsName));
        return results;
    }

    private static <D extends Data> ResolverResult<D> resolveWithFallback(DnsName dnsName, Class<D> type) throws IOException {
        final Question question = new Question(dnsName, Record.TYPE.getType(type));
        if (!DNSSECLESS_TLDS.contains(dnsName.getLabels()[0].toString())) {
            try {
                ResolverResult<D> result = DnssecResolverApi.INSTANCE.resolve(question);
                if (result.wasSuccessful() && !result.isAuthenticData()) {
                    Log.d(Config.LOGTAG, "DNSSEC validation failed for " + type.getSimpleName() + " : " + result.getUnverifiedReasons());
                }
                return result;
            } catch (DnssecValidationFailedException e) {
                Log.d(Config.LOGTAG, Resolver.class.getSimpleName() + ": error resolving " + type.getSimpleName() + " with DNSSEC. Trying DNS instead.", e);
            } catch (IOException e) {
                throw e;
            } catch (Throwable throwable) {
                Log.d(Config.LOGTAG, Resolver.class.getSimpleName() + ": error resolving " + type.getSimpleName() + " with DNSSEC. Trying DNS instead.", throwable);
            }
        }
        return ResolverApi.INSTANCE.resolve(question);
    }

    public static class Result implements Comparable<Result> {
        public static final String DOMAIN = "domain";
        public static final String IP = "ip";
        public static final String HOSTNAME = "hostname";
        public static final String PORT = "port";
        public static final String PRIORITY = "priority";
        public static final String DIRECT_TLS = "directTls";
        public static final String AUTHENTICATED = "authenticated";
        private InetAddress ip;
        private DnsName hostname;
        private int port = DEFAULT_PORT_XMPP;
        private boolean directTls = false;
        private boolean authenticated = false;
        private int priority;

        static Result fromRecord(SRV srv, boolean directTls) {
            Result result = new Result();
            result.port = srv.port;
            result.hostname = srv.name;
            result.directTls = directTls;
            result.priority = srv.priority;
            return result;
        }

        static Result createDefault(DnsName hostname, InetAddress ip) {
            Result result = new Result();
            result.port = DEFAULT_PORT_XMPP;
            result.hostname = hostname;
            result.ip = ip;
            return result;
        }

        static Result createDefault(DnsName hostname) {
            return createDefault(hostname, null);
        }

        public static Result fromCursor(Cursor cursor) {
            final Result result = new Result();
            try {
                result.ip = InetAddress.getByAddress(cursor.getBlob(cursor.getColumnIndex(IP)));
            } catch (UnknownHostException e) {
                result.ip = null;
            }
            final String hostname = cursor.getString(cursor.getColumnIndex(HOSTNAME));
            result.hostname = hostname == null ? null : DnsName.from(hostname);
            result.port = cursor.getInt(cursor.getColumnIndex(PORT));
            result.priority = cursor.getInt(cursor.getColumnIndex(PRIORITY));
            result.authenticated = cursor.getInt(cursor.getColumnIndex(AUTHENTICATED)) > 0;
            result.directTls = cursor.getInt(cursor.getColumnIndex(DIRECT_TLS)) > 0;
            return result;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Result result = (Result) o;

            if (port != result.port) return false;
            if (directTls != result.directTls) return false;
            if (authenticated != result.authenticated) return false;
            if (priority != result.priority) return false;
            if (ip != null ? !ip.equals(result.ip) : result.ip != null) return false;
            return hostname != null ? hostname.equals(result.hostname) : result.hostname == null;
        }

        @Override
        public int hashCode() {
            int result = ip != null ? ip.hashCode() : 0;
            result = 31 * result + (hostname != null ? hostname.hashCode() : 0);
            result = 31 * result + port;
            result = 31 * result + (directTls ? 1 : 0);
            result = 31 * result + (authenticated ? 1 : 0);
            result = 31 * result + priority;
            return result;
        }

        public InetAddress getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        public DnsName getHostname() {
            return hostname;
        }

        public boolean isDirectTls() {
            return directTls;
        }

        public boolean isAuthenticated() {
            return authenticated;
        }

        @Override
        public String toString() {
            return "Result{" +
                    "ip='" + (ip == null ? null : ip.getHostAddress()) + '\'' +
                    ", hostame='" + (hostname == null ? null : hostname.toString()) + '\'' +
                    ", port=" + port +
                    ", directTls=" + directTls +
                    ", authenticated=" + authenticated +
                    ", priority=" + priority +
                    '}';
        }

        @Override
        public int compareTo(@NonNull Result result) {
            if (result.priority == priority) {
                if (directTls == result.directTls) {
                    if (ip == null && result.ip == null) {
                        return 0;
                    } else if (ip != null && result.ip != null) {
                        if (ip instanceof Inet4Address && result.ip instanceof Inet4Address) {
                            return 0;
                        } else {
                            return ip instanceof Inet4Address ? -1 : 1;
                        }
                    } else {
                        return ip != null ? -1 : 1;
                    }
                } else {
                    return directTls ? -1 : 1;
                }
            } else {
                return priority - result.priority;
            }
        }

        public ContentValues toContentValues() {
            final ContentValues contentValues = new ContentValues();
            contentValues.put(IP, ip == null ? null : ip.getAddress());
            contentValues.put(HOSTNAME, hostname == null ? null : hostname.toString());
            contentValues.put(PORT, port);
            contentValues.put(PRIORITY, priority);
            contentValues.put(DIRECT_TLS, directTls ? 1 : 0);
            contentValues.put(AUTHENTICATED, authenticated ? 1 : 0);
            return contentValues;
        }

        public Result seeOtherHost(final String seeOtherHost) {
            final String hostname = seeOtherHost.trim();
            if (hostname.isEmpty()) {
                return null;
            }
            final Result result = new Result();
            result.directTls = this.directTls;
            final int portSegmentStart = hostname.lastIndexOf(':');
            if (hostname.charAt(hostname.length() - 1) != ']'
                    && portSegmentStart >= 0
                    && hostname.length() >= portSegmentStart + 1) {
                final String hostPart = hostname.substring(0, portSegmentStart);
                final String portPart = hostname.substring(portSegmentStart + 1);
                final Integer port = Ints.tryParse(portPart);
                if (port == null || Strings.isNullOrEmpty(hostPart)) {
                    return null;
                }
                final String host = eu.siacs.conversations.utils.IP.unwrapIPv6(hostPart);
                result.port = port;
                if (InetAddresses.isInetAddress(host)) {
                    final InetAddress inetAddress;
                    try {
                        inetAddress = InetAddresses.forString(host);
                    } catch (final IllegalArgumentException e) {
                        return null;
                    }
                    result.ip = inetAddress;
                } else {
                    if (hostPart.trim().isEmpty()) {
                        return null;
                    }
                    try {
                        result.hostname = DnsName.from(hostPart.trim());
                    } catch (final Exception e) {
                        return null;
                    }
                }
            } else {
                final String host = eu.siacs.conversations.utils.IP.unwrapIPv6(hostname);
                if (InetAddresses.isInetAddress(host)) {
                    final InetAddress inetAddress;
                    try {
                        inetAddress = InetAddresses.forString(host);
                    } catch (final IllegalArgumentException e) {
                        return null;
                    }
                    result.ip = inetAddress;
                } else {
                    try {
                        result.hostname = DnsName.from(hostname);
                    } catch (final Exception e) {
                        return null;
                    }
                }
                result.port = port;
            }
            return result;
        }
    }

}
