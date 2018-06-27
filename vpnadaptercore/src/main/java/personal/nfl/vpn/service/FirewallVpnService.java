package personal.nfl.vpn.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

import personal.nfl.vpn.Packet;
import personal.nfl.vpn.ProxyConfig;
import personal.nfl.vpn.R;
import personal.nfl.vpn.UDPServer;
import personal.nfl.vpn.VPNConstants;
import personal.nfl.vpn.VPNLog;
import personal.nfl.vpn.http.HttpRequestHeaderParser;
import personal.nfl.vpn.nat.NatSession;
import personal.nfl.vpn.nat.NatSessionManager;
import personal.nfl.vpn.processparse.PortHostService;
import personal.nfl.vpn.proxy.TcpProxyServer;
import personal.nfl.vpn.tcpip.IPHeader;
import personal.nfl.vpn.tcpip.TCPHeader;
import personal.nfl.vpn.tcpip.UDPHeader;
import personal.nfl.vpn.utils.AppDebug;
import personal.nfl.vpn.utils.CommonMethods;
import personal.nfl.vpn.utils.DebugLog;
import personal.nfl.vpn.utils.ThreadProxy;
import personal.nfl.vpn.utils.TimeFormatUtil;
import personal.nfl.vpn.utils.VpnServiceHelper;


/**
 * 自定义 VPN 服务
 */
public class FirewallVpnService extends VpnService implements Runnable {

    public static final String ACTION_START_VPN = "personal.nfl.START_VPN";
    public static final String ACTION_CLOSE_VPN = "personal.nfl.roav.CLOSE_VPN";
    private static final String FACEBOOK_APP = "com.facebook.katana";
    private static final String YOUTUBE_APP = "com.google.android.youtube";
    private static final String GOOGLE_MAP_APP = "com.google.android.apps.maps";

    private static final String VPN_ADDRESS = "10.0.0.2"; // Only IPv4 support for now
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything
    private static final String GOOGLE_DNS_FIRST = "8.8.8.8";
    private static final String GOOGLE_DNS_SECOND = "8.8.4.4";
    private static final String AMERICA = "208.67.222.222";
    private static final String HK_DNS_SECOND = "205.252.144.228";
    private static final String CHINA_DNS_FIRST = "114.114.114.114";
    public static final String BROADCAST_VPN_STATE = "personal.nfl.localvpn.VPN_STATE";
    public static final String SELECT_PACKAGE_ID = "select_protect_package_id";
    private static final String TAG = "FirewallVpnService";
    // 用于标记新创建的 Vpn 服务
    private static int ID;
    // 本地 ip
    private static int LOCAL_IP;
    // IP 报文格式
    private IPHeader mIPHeader;
    // UDP 报文格式
    private TCPHeader mTCPHeader;
    // UDP 报文格式
    private final UDPHeader mUDPHeader;
    // UDP 代理服务
    private TcpProxyServer mTcpProxyServer;
    // UDP 代理服务
    private UDPServer udpServer;
    private final ByteBuffer mDNSBuffer;
    private boolean IsRunning = false;
    private Thread mVPNThread;
    private ParcelFileDescriptor mVPNInterface;

    // private DnsProxy mDnsProxy;
    private FileOutputStream mVPNOutputStream;

    private byte[] mPacket;

    private Handler mHandler;
    private ConcurrentLinkedQueue<Packet> udpQueue;
    private FileInputStream in;

    // 选择抓取特定 app 的包，默认是 null
    private String selectPackage;
    public static final int MUTE_SIZE = 2560;
    private int mReceivedBytes;
    private int mSentBytes;
    public static long vpnStartTime;
    public static String lastVpnStartTimeFormat = null;
    private SharedPreferences sp;

    public FirewallVpnService() {
        ID++;
        mHandler = new Handler();
        mPacket = new byte[MUTE_SIZE];
        mIPHeader = new IPHeader(mPacket, 0);
        // Offset = ip 报文头部长度
        mTCPHeader = new TCPHeader(mPacket, 20);
        mUDPHeader = new UDPHeader(mPacket, 20);
        // Offset = ip 报文头部长度 + udp 报文头部长度 = 28
        mDNSBuffer = ((ByteBuffer) ByteBuffer.wrap(mPacket).position(28)).slice();
        DebugLog.i("New VPNService(%d)\n", ID);
    }

    /**
     * 启动 Vpn 工作线程
     */
    @Override
    public void onCreate() {
        super.onCreate();
        DebugLog.i("VPNService(%s) created.\n", ID);
        sp = getSharedPreferences(VPNConstants.VPN_SP_NAME, Context.MODE_PRIVATE);
        VpnServiceHelper.onVpnServiceCreated(this);
        mVPNThread = new Thread(this, "VPNServiceThread");
        mVPNThread.start();
        setVpnRunningStatus(true);
    }

    @Override
    public void run() {
        try {
            DebugLog.i("VPNService(%s) work thread is Running...\n", ID);
            waitUntilPrepared();
            udpQueue = new ConcurrentLinkedQueue<>();
            // 启动 TCP 代理服务
            mTcpProxyServer = new TcpProxyServer(0);
            mTcpProxyServer.start();
            // 启动 UDP 代理服务
            udpServer = new UDPServer(this, udpQueue);
            udpServer.start();
            NatSessionManager.clearAllSession();
            if (PortHostService.getInstance() != null) {
                PortHostService.startParse(getApplicationContext());
            }
            DebugLog.i("DnsProxy started.\n");

            ProxyConfig.Instance.onVpnStart(this);
            while (IsRunning) {
                runVPN();
            }
        } catch (InterruptedException e) {
            if (AppDebug.IS_DEBUG) {
                e.printStackTrace();
            }
            DebugLog.e("VpnService run catch an exception %s.\n", e);
        } catch (Exception e) {
            if (AppDebug.IS_DEBUG) {
                e.printStackTrace();
            }
            DebugLog.e("VpnService run catch an exception %s.\n", e);
        } finally {
            DebugLog.i("VpnService terminated");
            ProxyConfig.Instance.onVpnEnd(this);
            dispose();
        }
    }

    /**
     * 停止Vpn工作线程
     */
    @Override
    public void onDestroy() {
        DebugLog.i("VPNService(%s) destroyed.\n", ID);
        if (mVPNThread != null) {
            mVPNThread.interrupt();
        }
        VpnServiceHelper.onVpnServiceDestroy();
        super.onDestroy();
    }

    /**
     * 建立VPN，同时监听出口流量
     */
    private void runVPN() throws Exception {
        this.mVPNInterface = establishVPN();
        startStream();
    }

    /**
     * 创建 VPN
     *
     * @return
     * @throws Exception
     */
    private ParcelFileDescriptor establishVPN() throws Exception {
        VpnService.Builder builder = new Builder()
                // 设置 VPN 最大传输单位
                .setMtu(MUTE_SIZE)
                // 设置 VPN 路由
                .addRoute(VPN_ROUTE, 0)
                // 配置 DNS
                .addDnsServer(GOOGLE_DNS_FIRST)
                .addDnsServer(GOOGLE_DNS_SECOND)
                .addDnsServer(CHINA_DNS_FIRST)
                .addDnsServer(AMERICA)
                /*
                 * Set the name of this session. It will be displayed in system-managed dialogs
                 * and notifications. This is recommended not required.
                 */
                .setSession(getString(R.string.app_name));
        ;
        selectPackage = sp.getString(VPNConstants.DEFAULT_PACKAGE_ID, null);
        DebugLog.i("setMtu: %d\n", ProxyConfig.Instance.getMTU());
        ProxyConfig.IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);
        // ipAddress.PrefixLength 默认值是 32 ，现只支持 IPv4 不支持 IPv6
        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);
        DebugLog.i("addAddress: %s/%d\n", ipAddress.Address, ipAddress.PrefixLength);
        vpnStartTime = System.currentTimeMillis();
        lastVpnStartTimeFormat = TimeFormatUtil.formatYYMMDDHHMMSS(vpnStartTime);
        try {
            if (selectPackage != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    builder.addAllowedApplication(selectPackage);
                    builder.addAllowedApplication(getPackageName());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        ParcelFileDescriptor pfdDescriptor = builder.establish();
        //  notifyStatus(new VPNEvent(VPNEvent.Status.ESTABLISHED));
        return pfdDescriptor;
    }

    private void startStream() throws Exception {
        int size = 0;
        mVPNOutputStream = new FileOutputStream(mVPNInterface.getFileDescriptor());
        in = new FileInputStream(mVPNInterface.getFileDescriptor());
        while (size != -1 && IsRunning) {
            boolean hasWrite = false;
            size = in.read(mPacket);
            if (size > 0) {
                if (mTcpProxyServer.Stopped) {
                    in.close();
                    throw new Exception("LocalServer stopped.");
                }
                hasWrite = onIPPacketReceived(mIPHeader, size);
            }
            if (!hasWrite) {
                Packet packet = udpQueue.poll();
                if (packet != null) {
                    ByteBuffer bufferFromNetwork = packet.backingBuffer;
                    bufferFromNetwork.flip();
                    mVPNOutputStream.write(bufferFromNetwork.array());
                }
            }
            Thread.sleep(10);
        }
        in.close();
        disconnectVPN();
    }

    /**
     * 死循环知道 VPN 准备好
     */
    private void waitUntilPrepared() {
        while (prepare(this) != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                if (AppDebug.IS_DEBUG) {
                    e.printStackTrace();
                }
                DebugLog.e("waitUntilPrepared catch an exception %s\n", e);
            }
        }
    }

    private void disconnectVPN() {
        try {
            if (mVPNInterface != null) {
                mVPNInterface.close();
                mVPNInterface = null;
            }
        } catch (Exception e) {
            //ignore
        }
        // notifyStatus(new VPNEvent(VPNEvent.Status.UNESTABLISHED));
        this.mVPNOutputStream = null;
    }

    private synchronized void dispose() {
        try {
            // 断开VPN
            disconnectVPN();
            // 停止TCP代理服务
            if (mTcpProxyServer != null) {
                mTcpProxyServer.stop();
                mTcpProxyServer = null;
                DebugLog.i("TcpProxyServer stopped.\n");
            }
            if (udpServer != null) {
                udpServer.closeAllUDPConn();
            }
            ThreadProxy.getInstance().execute(new Runnable() {
                @Override
                public void run() {
                    if (PortHostService.getInstance() != null) {
                        PortHostService.getInstance().refreshSessionInfo();
                    }
                    PortHostService.stopParse(getApplicationContext());
                }
            });
            stopSelf();
            setVpnRunningStatus(false);
        } catch (Exception e) {
        }
    }

    public boolean vpnRunningStatus() {
        return IsRunning;
    }

    public void setVpnRunningStatus(boolean isRunning) {
        IsRunning = isRunning;
    }

    boolean onIPPacketReceived(IPHeader ipHeader, int size) throws IOException {
        boolean hasWrite = false;
        switch (ipHeader.getProtocol()) {
            case IPHeader.TCP:
                hasWrite = onTcpPacketReceived(ipHeader, size);
                break;
            case IPHeader.UDP:
                onUdpPacketReceived(ipHeader, size);
                break;
            default:
                break;
        }
        return hasWrite;

    }

    private void onUdpPacketReceived(IPHeader ipHeader, int size) throws UnknownHostException {
        TCPHeader tcpHeader = mTCPHeader;
        short portKey = tcpHeader.getSourcePort();


        NatSession session = NatSessionManager.getSession(portKey);
        if (session == null || session.remoteIP != ipHeader.getDestinationIP() || session.remotePort
                != tcpHeader.getDestinationPort()) {
            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader
                    .getDestinationPort(), NatSession.UDP);
            session.vpnStartTime = vpnStartTime;
            ThreadProxy.getInstance().execute(new Runnable() {
                @Override
                public void run() {
                    if (PortHostService.getInstance() != null) {
                        PortHostService.getInstance().refreshSessionInfo();
                    }

                }
            });
        }

        session.lastRefreshTime = System.currentTimeMillis();
        session.packetSent++; //注意顺序

        byte[] bytes = Arrays.copyOf(mPacket, mPacket.length);
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, 0, size);
        byteBuffer.limit(size);
        Packet packet = new Packet(byteBuffer);
        udpServer.processUDPPacket(packet, portKey);
    }

    private boolean onTcpPacketReceived(IPHeader ipHeader, int size) throws IOException {
        boolean hasWrite = false;
        TCPHeader tcpHeader = mTCPHeader;
        //矫正TCPHeader里的偏移量，使它指向真正的TCP数据地址
        tcpHeader.mOffset = ipHeader.getHeaderLength();
        if (tcpHeader.getSourcePort() == mTcpProxyServer.port) {
            VPNLog.d(TAG, "process  tcp packet from net ");
            NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());
            if (session != null) {
                ipHeader.setSourceIP(ipHeader.getDestinationIP());
                tcpHeader.setSourcePort(session.remotePort);
                ipHeader.setDestinationIP(LOCAL_IP);

                CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                mVPNOutputStream.write(ipHeader.mData, ipHeader.mOffset, size);
                mReceivedBytes += size;
            } else {
                DebugLog.i("NoSession: %s %s\n", ipHeader.toString(), tcpHeader.toString());
            }
            Log.d("NFL" , "app name : " + (session.getAppInfo() == null ? "null" : session.getAppInfo().pkgs.getAt(0))) ;
        } else {
            VPNLog.d(TAG, "process  tcp packet to net ");
            // 添加端口映射
            short portKey = tcpHeader.getSourcePort();
            NatSession session = NatSessionManager.getSession(portKey);
            if (session == null || session.remoteIP != ipHeader.getDestinationIP() || session.remotePort
                    != tcpHeader.getDestinationPort()) {
                session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader
                        .getDestinationPort(), NatSession.TCP);
                session.vpnStartTime = vpnStartTime;
                ThreadProxy.getInstance().execute(new Runnable() {
                    @Override
                    public void run() {
                        PortHostService instance = PortHostService.getInstance();
                        if (instance != null) {
                            instance.refreshSessionInfo();
                        }

                    }
                });
            }

            session.lastRefreshTime = System.currentTimeMillis();
            session.packetSent++; //注意顺序
            int tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength();
            //丢弃tcp握手的第二个ACK报文。因为客户端发数据的时候也会带上ACK，这样可以在服务器Accept之前分析出HOST信息。
            if (session.packetSent == 2 && tcpDataSize == 0) {
                return false;
            }

            // 分析数据，找到host
            if (session.bytesSent == 0 && tcpDataSize > 10) {
                int dataOffset = tcpHeader.mOffset + tcpHeader.getHeaderLength();
                HttpRequestHeaderParser.parseHttpRequestHeader(session, tcpHeader.mData, dataOffset,
                        tcpDataSize);
                DebugLog.i("Host: %s\n", session.remoteHost);
                DebugLog.i("Request: %s %s\n", session.method, session.requestUrl);
            } else if (session.bytesSent > 0
                    && !session.isHttpsSession
                    && session.isHttp
                    && session.remoteHost == null
                    && session.requestUrl == null) {
                int dataOffset = tcpHeader.mOffset + tcpHeader.getHeaderLength();
                session.remoteHost = HttpRequestHeaderParser.getRemoteHost(tcpHeader.mData, dataOffset,
                        tcpDataSize);
                session.requestUrl = "http://" + session.remoteHost + "/" + session.pathUrl;
            }

            //转发给本地TCP服务器
            ipHeader.setSourceIP(ipHeader.getDestinationIP());
            ipHeader.setDestinationIP(LOCAL_IP);
            tcpHeader.setDestinationPort(mTcpProxyServer.port);

            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
            mVPNOutputStream.write(ipHeader.mData, ipHeader.mOffset, size);
            //注意顺序
            session.bytesSent += tcpDataSize;
            mSentBytes += size;
            Log.d("NFL" , "app name : " + (session.getAppInfo() == null ? "null" : session.getAppInfo().pkgs.getAt(0))) ;
        }
        hasWrite = true;
        return hasWrite;
    }
}
