package personal.nfl.networkcapture.fragment;


import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import personal.nfl.networkcapture.R;
import personal.nfl.networkcapture.activity.PacketDetailActivity;
import personal.nfl.networkcapture.adapter.ConnectionAdapter;
import personal.nfl.networkcapture.common.widget.BaseFragment;
import personal.nfl.vpn.ProxyConfig;
import personal.nfl.vpn.VPNConstants;
import personal.nfl.vpn.nat.NatSession;
import personal.nfl.vpn.utils.TimeFormatUtil;
import personal.nfl.vpn.utils.VpnServiceHelper;

import static personal.nfl.vpn.VPNConstants.DEFAULT_PACKAGE_ID;


/**
 * 抓包列表
 *
 * @author nfl
 */

public class CaptureFragment extends BaseFragment {

    private static final String TAG = "CaptureFragment";
    /**
     * 取消刷新抓包列表工具
     */
    private Disposable disposeCapture;
    private Handler handler = new Handler();
    private ConnectionAdapter connectionAdapter;
    private ListView channelList;
    private volatile List<NatSession> allNetConnection = new ArrayList<>();
    private Context context;
    private String selfPackageName;
    private SharedPreferences sp;
    private boolean isShowUDP;
    private String selectPackage;

    private String appPackageName;
    private Iterator<NatSession> iterator;
    private NatSession next;

    @Override
    protected int getLayout() {
        return R.layout.fragment_capture;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        context = getContext();
        channelList = view.findViewById(R.id.channel_list);
        channelList.setOnItemClickListener(onItemClickListener);
       /* LocalBroadcastManager.getInstance(getContext()).registerReceiver(vpnStateReceiver,
                new IntentFilter(LocalVPNService.BROADCAST_VPN_STATE));*/
        ProxyConfig.Instance.registerVpnStatusListener(listener);
        initData();
        startTimer();
    }

    private void initData() {
        selfPackageName = context.getPackageName();
        sp = context.getSharedPreferences(VPNConstants.VPN_SP_NAME, Context.MODE_PRIVATE);
        isShowUDP = sp.getBoolean(VPNConstants.IS_UDP_SHOW, false);
        selectPackage = sp.getString(DEFAULT_PACKAGE_ID, null);
        connectionAdapter = new ConnectionAdapter(context, allNetConnection);
        channelList.setAdapter(connectionAdapter);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ProxyConfig.Instance.unregisterVpnStatusListener(listener);
        cancelTimer();
    }

    private void refreshData() {

        allNetConnection.clear();
        // TODO 这里会获取大量 socket 信息，超过一定量要精简,而且前面的内容已经加载了一遍，这里还会重复加载
        List<NatSession> temp = VpnServiceHelper.getAllSession();
        if (null != temp && temp.size() > 0) {
            allNetConnection.addAll(temp);
        }
        Log.i("NFL", "抓包记录的个数：" + allNetConnection.size());
        //
        if (allNetConnection != null) {
            iterator = allNetConnection.iterator();
            // TODO 为了提高性能，这里应该在配置改变的时候利用通知，或共享变量来处理，而不是每次都从 sp 中获取
            isShowUDP = sp.getBoolean(VPNConstants.IS_UDP_SHOW, false);
            selectPackage = sp.getString(DEFAULT_PACKAGE_ID, null);
            while (iterator.hasNext()) {
                next = iterator.next();
                appPackageName = next.appInfo == null ? null : next.appInfo.pkgs.getAt(0);
                if ((next.bytesSent == 0 && next.receiveByteNum == 0)
                        || (!isShowUDP && NatSession.UDP.equals(next.type))// 是否显示 UDP 信息
                        || (selfPackageName.equals(appPackageName)) // 移除自己的网络请求
                        || ((!TextUtils.isEmpty(selectPackage) && !selectPackage.equals(appPackageName)))
                    // 如果选择了抓取特定 app 包，那么去掉其它的包
                        ) {
                    iterator.remove();
                    continue;
                }
            }
        }
    }

    /**
     * 每隔 1s 读取一次抓包信息
     */
    private void startTimer() {
        disposeCapture = Flowable.interval(2000, TimeUnit.MILLISECONDS)
                // .observeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(new Consumer<Long>() {
                    @Override
                    public void accept(Long aLong) throws Exception {
                        refreshData();
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .doAfterNext(new Consumer<Long>() {
                    @Override
                    public void accept(Long aLong) throws Exception {
                        connectionAdapter.notifyDataSetChanged();
                    }
                })
                .subscribe();
    }

    private void cancelTimer() {
        if (disposeCapture != null) {
            disposeCapture.dispose();
            disposeCapture = null;
        }
    }

    private ProxyConfig.VpnStatusListener listener = new ProxyConfig.VpnStatusListener() {

        @Override
        public void onVpnAvailable(Context context) {

        }

        @Override
        public void onVpnPreParing(Context context) {

        }

        @Override
        public void onVpnRunning(Context context) {
            startTimer();
        }

        @Override
        public void onVpnStopping(Context context) {

        }

        @Override
        public void onVpnStop(Context context) {
            cancelTimer();
        }
    };

    private OnItemClickListener onItemClickListener = new OnItemClickListener() {

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (allNetConnection == null || position > allNetConnection.size() - 1) {
                return;
            }
            NatSession connection = allNetConnection.get(position);
            if (connection.isHttpsSession) {
                // TODO HTTPS 待解决
                // return;
            }
            if (!NatSession.TCP.equals(connection.type)) {
                // TODO 不是 TCP 协议的话暂不解决
                return;
            }
            String dir = VPNConstants.DATA_DIR
                    + TimeFormatUtil.formatYYMMDDHHMMSS(connection.vpnStartTime)
                    + "/"
                    + connection.getUniqueName();
            PacketDetailActivity.startActivity(getActivity(), dir);
        }
    };
}

