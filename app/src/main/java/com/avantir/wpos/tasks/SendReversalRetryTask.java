package com.avantir.wpos.tasks;

import android.content.Context;
import android.os.AsyncTask;
import com.avantir.wpos.WPOSApplication;
import com.avantir.wpos.dao.ReversalInfoDao;
import com.avantir.wpos.dao.TransInfoDao;
import com.avantir.wpos.model.ReversalInfo;
import com.avantir.wpos.model.TransInfo;
import com.avantir.wpos.services.TcpComms;
import com.avantir.wpos.utils.*;

import java.util.Date;
import java.util.List;

/**
 * Created by lekanomotayo on 30/01/2018.
 */
public class SendReversalRetryTask extends AsyncTask<Void, Void, Boolean> {

    private static final String TAG = "ProcessReversalTask";
    private final Context mApplicationContext;
    GlobalData globalData;
    private TcpComms comms;
    ReversalInfoDao reversalInfoDao;
    TransInfoDao transInfoDao;


    public SendReversalRetryTask(Context context) {
        mApplicationContext = context.getApplicationContext();
        initData();
    }

    protected void initData() {
        globalData = GlobalData.getInstance();
        comms = new TcpComms(globalData.getCTMSIP(), globalData.getCTMSPort(), globalData.getCTMSTimeout(), globalData.getIfCTMSSSL(), null);
        reversalInfoDao  = new ReversalInfoDao(WPOSApplication.app);
        transInfoDao = new TransInfoDao(WPOSApplication.app);
    }

    @Override
    protected void onProgressUpdate(final Void... values) {

    }

    @Override
    protected Boolean doInBackground(Void... voids) {

        // Fetch all open transaction
        try{
            //List<TransInfo> transInfoList1 = transInfoDao.findAll();
            List<TransInfo> transInfoList = transInfoDao.findAllOpenTransaction();
            if(transInfoList != null){
                for(TransInfo transInfo: transInfoList){
                    try{
                        long now = TimeUtil.getTimeInEpoch(new Date(System.currentTimeMillis()));
                        long diff = now - transInfo.getCreatedOn();
                        if(diff > (60 * 60 * 1000)){ // set this to about 1hr
                            ReversalInfo reversalInfo = reversalInfoDao.findByRetRefNo(transInfo.getRetRefNo());
                            if(reversalInfo == null){
                                reversalInfo = IsoMessageUtil.createReversalInfo(transInfo, ConstantUtils.MSG_REASON_CODE_TIMEOUT_WAITING_FOR_RESPONSE);
                                reversalInfo.setCreatedOn(TimeUtil.getTimeInEpoch(new Date(System.currentTimeMillis())));
                                reversalInfoDao.create(reversalInfo);
                            }
                            //transInfoDao.updateResponseCodeAuthNumCompletedByRetRefNo(transInfo.getRetRefNo(), 1);
                            transInfo.setEndTime(System.currentTimeMillis());
                            transInfo.setLatency(transInfo.getEndTime() - transInfo.getStartTime());
                            transInfoDao.updateResponseCodeAuthNumCompletedByRetRefNo(transInfo.getRetRefNo(), transInfo.getResponseCode(), "", 91, transInfo.getLatency());
                        }
                    }
                    catch(Exception ex){
                        ex.printStackTrace();
                    }
                }
            }


            // Fetch all open reversal
            //List<ReversalInfo> reversalInfoList1 = reversalInfoDao.findAll();
            List<ReversalInfo> reversalInfoList = reversalInfoDao.findAllOpenTransaction();
            //System.out.println("Is terminal idle: " );
            if(reversalInfoList != null){
                for(ReversalInfo reversalInfo: reversalInfoList){
                    try{
                        int retry = reversalInfo.getRetryNo();
                        boolean isRepeat = false;
                        if(retry > 0)
                            isRepeat = true;
                        retry++;
                        String retRefNo = reversalInfo.getRetRefNo();
                        reversalInfoDao.updateRetryByRetRefNo(retRefNo, retry);
                        //TcpComms comms = new TcpComms(globalData.getCTMSIP(), globalData.getCTMSPort(), globalData.getCTMSTimeout(), globalData.getIfCTMSSSL(), null);
                        reversalInfo.setStartTime(System.currentTimeMillis());
                        String responseCode = NIBSSRequests.doReversal(reversalInfo, isRepeat, comms);
                        reversalInfo.setResponseCode(StringUtil.isEmpty(responseCode) ? "" : responseCode);
                        reversalInfo.setCompleted(1);
                        reversalInfo.setEndTime(System.currentTimeMillis());
                        reversalInfo.setLatency(reversalInfo.getEndTime() - reversalInfo.getStartTime());
                        reversalInfoDao.updateResponseCodeCompletionByRetRefNo(reversalInfo.getRetRefNo(), reversalInfo.getResponseCode(), reversalInfo.getCompleted(), reversalInfo.getLatency());
                        transInfoDao.updateReversalCompletionByRetRefNo(retRefNo, 1, 1);
                    }
                    catch(Exception ex){
                        ex.printStackTrace();
                    }
                }
            }
        }
        catch(Exception ex){
            ex.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    protected void onPostExecute(Boolean success) {

    }

}
