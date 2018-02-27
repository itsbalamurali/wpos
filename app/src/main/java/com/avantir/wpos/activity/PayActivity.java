package com.avantir.wpos.activity;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;
import com.avantir.wpos.R;
import com.avantir.wpos.WPOSApplication;
import com.avantir.wpos.dao.ReversalInfoDao;
import com.avantir.wpos.dao.TransInfoDao;
import com.avantir.wpos.dialog.KeyPadDialog;
import com.avantir.wpos.interfaces.ICommsListener;
import com.avantir.wpos.model.ReversalInfo;
import com.avantir.wpos.utils.IsoMessageUtil;
import com.avantir.wpos.listeners.CommsListener;
import com.avantir.wpos.listeners.PINPadListener;
import com.avantir.wpos.listeners.EMVPINPadListener;
import com.avantir.wpos.model.TransInfo;
import com.avantir.wpos.services.TcpComms;
import com.avantir.wpos.services.EMVManager;
import com.avantir.wpos.threads.PrintThread;
import com.avantir.wpos.utils.*;
import com.solab.iso8583.IsoMessage;
import sdk4.wangpos.libemvbinder.EmvCore;
import wangpos.sdk4.emv.ICallbackListener;
import wangpos.sdk4.libbasebinder.BankCard;
import wangpos.sdk4.libbasebinder.Core;
import wangpos.sdk4.libbasebinder.Printer;

import java.util.Date;
import java.util.concurrent.CountDownLatch;

/**
 * Created by lekanomotayo on 24/01/2018.
 */
public class PayActivity extends BaseActivity {

    private String TAG = "PayActivity";

    private TextView tranStatusText;
    private TextView infoText;
    //private String strTxt = "";
    public static final int MSG_START_TRANS = 0x2000;

    private Bundle bundle;

    private boolean isOffLine = false;
    //order amount
    private int orderAmount;
    //Transaction type flag
    private int tranTypeFlag = ConstantUtils.PURCHASE;
    private int paymentInstrumentFlag = ConstantUtils.BANK_CARD;

    GlobalData globalData;
    private Context context;
    private Core mCore;
    private EmvCore emvCore;
    private BankCard mBankCard;
    TransInfo transInfo;
    ReversalInfo reversalInfo;
    private Printer mPrinter;

    TransInfoDao transInfoDao;
    ReversalInfoDao reversalInfoDao;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_pay_display);
        findViewById(R.id.titleBackImage).setVisibility(View.GONE);
        findViewById(R.id.titleSettingsImage).setVisibility(View.GONE);
        super.onCreate(savedInstanceState);
    }

    //@Override
    protected void initView() {
        //Top title bar
        ImageView titleBackImage = (ImageView) findViewById(R.id.titleBackImage);
        titleBackImage.setOnClickListener(this);
        TextView titleNameText = (TextView) findViewById(R.id.titleNameText);
        titleNameText.setText("Cashier");

        tranStatusText = (TextView) findViewById(R.id.transactionStatusText);
        infoText = (TextView) findViewById(R.id.infoText);
    }

    @Override
    protected void initData() {

        context = this;
        bundle = getIntent().getExtras();
        if(bundle == null)
        {
            bundle = new Bundle();
        }
        tranTypeFlag = bundle.getInt(ConstantUtils.TRAN_TYPE, ConstantUtils.PURCHASE);
        paymentInstrumentFlag = bundle.getInt(ConstantUtils.PAYMENT_INSTRUMENT, ConstantUtils.BANK_CARD);
        orderAmount = bundle.getInt(ConstantUtils.TRAN_AMT, 0);


        transInfoDao = new TransInfoDao(WPOSApplication.app);
        reversalInfoDao = new ReversalInfoDao(WPOSApplication.app);
        //transInfo = TransInfo.getInstance();
        //transInfo.init();
        transInfo = new TransInfo();
        reversalInfo = new ReversalInfo();
        globalData = GlobalData.getInstance();


        new Thread(new Runnable() {
            @Override
            public void run() {
                emvCore = new EmvCore(getApplicationContext());
                mCore = new Core(getApplicationContext());
                mBankCard = new BankCard(getApplicationContext());
                mPrinter = new Printer(getApplicationContext());

                try{
                    transInfo.setDeviceSerialNo(Build.SERIAL);
                    transInfo.setTradeType(ConstantUtils.Type_Sale);
                    transInfo.setMerchantId(globalData.getMerchantId());
                    transInfo.setMerchantName(globalData.getMerchantName());
                    transInfo.setTerminalId(globalData.getTerminalId());
                    int stan = (globalData.getStan() + 1) % 999999;
                    if(stan == 0)
                        stan = 1;
                    globalData.setStan(stan);
                    transInfo.setStan(StringUtil.leftPadding('0', 6, String.valueOf(stan)));
                    long retRef = (globalData.getRetrievalRef() + 1) % 999999999999L;
                    if(retRef == 0)
                        retRef = 1;
                    globalData.setRetrievalRef(retRef);
                    transInfo.setRetRefNo(StringUtil.leftPad(String.valueOf(retRef), 12, '0'));
                    transInfo.setAmt(String.valueOf(orderAmount));
                    transInfo.setOnLine(true);// Default is online transaction
                }
                catch(Exception ex){
                    ex.printStackTrace();
                }

                startTransaction();

            }
        }).start();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId())
        {
            case R.id.titleBackImage:
                finish();
                skipActivityAnim(-1);
                break;
            default:
                break;

        }
    }


    @Override
    protected void handleMessage(Message msg) {
        Log.i(PayActivity.class.getSimpleName(), "handleMessage: "+msg.what);
        switch (msg.what) {
            case 2:
                setTransactionStatusActionTip("get card information success,start trading ,pls connect your services ...");
                break;
            case MSG_ERROR:
                //myHandler.sendMessage(myHandler.obtainMessage(WhetherRetryVisiableTrue, msg.obj));
                setTransactionStatusActionTip(msg.obj + "");
                break;
            case MSG_PROGRESS:
                //myHandler.sendMessage(myHandler.obtainMessage(WhetherRetryVisiableFlase, msg.obj));
                setTransactionStatusActionTip(msg.obj + "");
                break;
            case Hide_Progress:
               // Hide the progress
                hideTransactionStatusActionTip();
                break;
            case MSG_START_TRANS:
                // start processing card things
                break;
            case MSG_START_COMMS:
                // start processing transaction online
                doPurchase();
                break;
            case MSG_FINISH_COMMS:
                // end comms
                byte[] receiveData = (byte[])msg.obj;
                processResponse(msg.getData(), receiveData);
                baseHandler.obtainMessage(MSG_INFO, "Please remove card").sendToTarget();
                break;
            case  MSG_FINISH_ERROR_COMMS:
                // end comms with error
                processFailedResponse(Integer.parseInt(msg.obj.toString()), msg.getData());
                baseHandler.obtainMessage(MSG_INFO, "Please remove card").sendToTarget();
                break;
            case MSG_INFO:
                // Hide the progress
                setInfoActionTip(msg.obj.toString());
                break;
            case MSG_START_PRINT:
                // Print Receipt
                //new PrintThread(mPrinter, baseHandler, transInfo, true).start();
                break;
            case MSG_FINISH_PRINT:
                // go to main page
                finishAndReturnMainActivity();
                break;
        }
    }

    private void startTransaction() {
        baseHandler.obtainMessage(MSG_PROGRESS, "waiting read card...").sendToTarget();

        try {
            mBankCard.breakOffCommand();//结束上一笔交易的命令(Order to close the previous transaction)
            byte[] outData = new byte[512];
            int[] outDataLen = new int[1];
            int result = mBankCard.readCard(BankCard.CARD_TYPE_NORMAL, BankCard.CARD_MODE_PICC|BankCard.CARD_MODE_ICC|BankCard.CARD_MODE_MAG,0x60,outData,outDataLen,"app1");
            baseHandler.obtainMessage(MSG_INFO, "Please do not remove card").sendToTarget();
            if (result == 0) {
                Log.d("outData", ByteUtil.bytes2HexString(outData));
                switch (outData[0]) {
                    case 0x01:
                        //Read card failed
                        baseHandler.obtainMessage(MSG_ERROR, "Failed to read card").sendToTarget();
                        break;
                    case 0x02:
                        //Read card success,but encryption processing failed
                        baseHandler.obtainMessage(MSG_ERROR, "Readcard, but encryption processing failed").sendToTarget();
                        break;
                    case 0x03:
                        //Read card timeout
                        baseHandler.obtainMessage(MSG_ERROR, "Timeout reading card").sendToTarget();
                        break;
                    case 0x04:
                        //Cancel read card
                        baseHandler.obtainMessage(MSG_ERROR, "Cancelled while reading card").sendToTarget();
                        break;
                    case 0x05:
                        //Read card success,type ICC
                        baseHandler.obtainMessage(MSG_PROGRESS, "Chip (IC) card detected").sendToTarget();
                        mCore.buzzer();
                        readCardInfo(ConstantUtils.ICC_CARD_TYPE);
                        break;
                    //case 0x07:
                    //Read card success,type PICC
                    // baseHandler.obtainMessage(MSG_PROGRESS, "Contactless card detected").sendToTarget();
                    //   mCore.buzzer();
                    //   readCardInfo(ConstantUtils.PICC_CARD_TYPE);
                    //   break;
                }
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }


    private void readCardInfo(String s) {
        isOffLine = false;
        baseHandler.obtainMessage(MSG_PROGRESS, "read card success ..." + s).sendToTarget();
        transInfo.setPosInputType(s);

        EMVManager.setEMVManager(context,baseHandler,emvCore);
        switch (s) {
            case ConstantUtils.ICC_CARD_TYPE:
                readEMVCardInfo();
                break;
            case ConstantUtils.PICC_CARD_TYPE:
                readContactlessCardInfo();
                break;
        }
    }


    private void readEMVCardInfo(){
        try {
            baseHandler.obtainMessage(MSG_PROGRESS, ConstantUtils.WAITING_MSG).sendToTarget();

            int result = EMVManager.PBOC_Simple(transInfo, iCallBackListener);

            if (result != ConstantUtils.EMV_OPERATION_SUCCESS) {
                baseHandler.obtainMessage(MSG_ERROR, "EMV-Process fail==" + result).sendToTarget();
            }
            else {
                int transType = transInfo.getTradeType();
                //Log.v(TAG, "PBOC_Simple。transType==" + transType);
                if (transType == ConstantUtils.Type_QueryBalance || transType == ConstantUtils.Type_Sale
                        || transType == ConstantUtils.Type_Auth || transType == ConstantUtils.Type_CoilingSale) {
                    // TODO: 2017/10/11 Blocking method, return the transaction result
                    int transResult = EMVManager.EMV_TransProcess(transInfo, iCallBackListener);
                    //Log.d(TAG,"checkResult=="+transResult);
                    if (transResult != -8) {
                        baseHandler.obtainMessage(MSG_ERROR, "EMV-Process fail").sendToTarget();
                    }
                } else {
                    displayPinPad(ConstantUtils.PICC_CARD_TYPE);
                }
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }


    private void readContactlessCardInfo(){
        try {
            baseHandler.obtainMessage(MSG_PROGRESS, ConstantUtils.WAITING_MSG).sendToTarget();
            int result = EMVManager.QPBOC_PreProcess(transInfo, iCallBackListener);
            if (result != ConstantUtils.EMV_OPERATION_SUCCESS) {
                //Log.d(TAG, "QPBOC_PreProcess fail，result==" + result);
            } else {
                result = EMVManager.PBOC_Simple(transInfo, iCallBackListener);
                if (result == ConstantUtils.EMV_OPERATION_SUCCESS) {
                    displayPinPad(ConstantUtils.PICC_CARD_TYPE);
                } else {
                    baseHandler.obtainMessage(MSG_ERROR, "PBOC fail result==" + result).sendToTarget();
                    //Log.d(TAG, "PBOC_Simple fail，result==" + result);
                }
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void displayPinPad(final String tradeType) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String cardNo = transInfo.getCardNo();
                KeyPadDialog.getInstance().showDialog((Activity) context, cardNo, new PINPadListener(baseHandler, iCallBackListener, transInfo, tradeType));
            }
        });
    }




    private CountDownLatch countDownLatch = null;
    private ICallbackListener iCallBackListener = new ICallbackListener.Stub() {
        @Override
        public int emvCoreCallback(final int command, final byte[] data, final byte[] result, final int[] resultlen) throws RemoteException {
            countDownLatch = new CountDownLatch(1);
            //Log.d(TAG, "emvCoreCallback。command==" + command);
            switch (command) {
                case 2818: //Core.CALLBACK_PIN
                    Log.i("iCallbackListener", "Core.CALLBACK_PIN");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            KeyPadDialog.getInstance().showDialog((Activity) context, command, data, result, resultlen, new EMVPINPadListener(baseHandler, countDownLatch));
                        }
                    });
                    break;
                case 2821://Core.CALLBACK_ONLINE
                    Log.i("iCallbackListener", "Core.CALLBACK_ONLINE");
                    //strTxt = "getCardInformation …";
                    //baseHandler.sendEmptyMessage(0);
                    baseHandler.obtainMessage(MSG_PROGRESS, "Sending transaction online").sendToTarget();
                    int ret = EMVManager.EMV_OnlineProc(result, resultlen,countDownLatch,baseHandler, transInfo);
                    Log.i("iCallbackListener", "Core.CALLBACK_ONLINE, ret = " + ret);
                    break;
                case 2823:
                    //strTxt = "OffLine pin check success";
                    //baseHandler.sendEmptyMessage(0);
                    baseHandler.obtainMessage(MSG_PROGRESS, "PIN Check succesfull").sendToTarget();
                    countDownLatch.countDown();
                    break;
                case 2817://Core.CALLBACK_NOTIFY
                    Log.i("iCallbackListener", "Core.CALLBACK_NOTIFY");
                    //app select
                    break;
                case 2819://Core.CALLBACK_AMOUNT //Set amount
                    long amount = orderAmount;
                    String amt = MoneyUtil.kobo2Naira(amount);
                    //Log.d(TAG, "amount==" + amount);
                    result[0] = 0;
                    //Log.d(TAG, "int2Bytes==" + (int) MoneyUtil.naira2Kobo(Double.parseDouble(a)));
                    byte[] tmp = ByteUtil.int2Bytes((int) (int) MoneyUtil.naira2Kobo(Double.parseDouble(amt)));
                    System.arraycopy(tmp, 0, result, 1, 4);
                    resultlen[0] = 9;
                    countDownLatch.countDown();
                    break;
            }
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 0;
        }
    };


    private void doPurchase(){
        byte[] data = null;
        try{
            transInfo.setMsgType(ConstantUtils._0200);
            transInfo.setProcCode(ConstantUtils.PURCHASE_PROC_CODE);
            Date now = new Date(System.currentTimeMillis());
            String transmissionDatetime = TimeUtil.getDateTimeMMddhhmmss(now);
            String localTime = TimeUtil.getTimehhmmss(now);
            String localDate = TimeUtil.getDateMMdd(now);
            transInfo.setTransmissionDateTime(transmissionDatetime);
            transInfo.setLocalTime(localTime);
            transInfo.setLocalDate(localDate);
            transInfo.setMerchType(globalData.getMerchantCategoryCode());
            String cardSeqNo = transInfo.getCardSequenceNo();
            transInfo.setCardSequenceNo(StringUtil.leftPadding("0", 3, cardSeqNo));
            transInfo.setPosConditionCode(ConstantUtils.NORMAL_PRESENTMENT_POS_CONDITION_CODE);
            transInfo.setPosPinCaptureCode(StringUtil.leftPadding("0", 2, String.valueOf(ConstantUtils.MAX_PIN_LENGTH)));
            transInfo.setPosEntryMode(transInfo.getPosInputType() + ConstantUtils.ACCEPT_PIN_MODE_CAPABILITY);
            transInfo.setSurcharge("C" + StringUtil.leftPadding("0", 7, globalData.getPurchaseSurcharge()));
            transInfo.setAcqInstId(globalData.getAcquirerId());
            transInfo.setMerchantLoc(IsoMessageUtil.getIso8583MerchantLoc(globalData.getMerchantLoc()));
            transInfo.setCurrencyCode(globalData.getCurrencyCode().substring(1));
            transInfo.setPosDataCode(globalData.getPOSDataCode());
            transInfo.setCreatedOn(System.currentTimeMillis());
            transInfoDao.create(transInfo);
            //transInfo = transInfoDao.findByRetRefNo(transInfo.getRetRefNo()); // get database ID

            data = IsoMessageUtil.createPurchase(transInfo);
            GlobalData globalData = GlobalData.getInstance();
            TcpComms comms = new TcpComms(globalData.getCTMSHost(), globalData.getCTMSPort(), globalData.getCTMSTimeout(), globalData.getIfCTMSSSL(), null);
            ICommsListener commsListener = new CommsListener(baseHandler, ConstantUtils.NETWORK_PURCHASE_REQ_TYPE);
            comms.dataCommu(this, data, commsListener);
        }
        catch(Exception ex){
            ex.printStackTrace();
            baseHandler.obtainMessage(MSG_ERROR, "Error while trying to ").sendToTarget();
            return;
        }
    }

    private void processResponse(Bundle bundle, byte[] receiveData){
        int reqType = bundle.getInt(ConstantUtils.NETWORK_REQ_TYPE);
        if(reqType == ConstantUtils.NETWORK_PURCHASE_REQ_TYPE) {
            doPurchaseResponse(receiveData);
            // log failed purchase response to database
        }
        //else if(reqType == ConstantUtils.NETWORK_PURCHASE_REQ_REVERSAL_TYPE) {
            // log reversal response to database, ask to do repeat reversal
        //}
    }

    private void  processFailedResponse(int commsErrorCode, Bundle bundle){
        int reqType = bundle.getInt(ConstantUtils.NETWORK_REQ_TYPE);
        if(reqType == ConstantUtils.NETWORK_PURCHASE_REQ_TYPE) {
            // 1  - connect error
            // 2  - sending error (error occuring during sending)
            // 3, 4, 5 - receiving error
            // 6 - disconnect error
            if(commsErrorCode == 2 || commsErrorCode == 3 || commsErrorCode == 4 || commsErrorCode == 5){
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try{
                            // Request may have got to server, do reversal
                            String msgReasonCode = ConstantUtils.MSG_REASON_CODE_TIMEOUT_WAITING_FOR_RESPONSE;
                            String retRefNo = transInfo.getRetRefNo();
                            reversalInfo = reversalInfoDao.findByRetRefNo(retRefNo);
                            boolean isRepeat = false;
                            if(reversalInfo == null) {
                                reversalInfo = IsoMessageUtil.createReversalInfo(transInfo, msgReasonCode);
                                reversalInfo.setCreatedOn(System.currentTimeMillis());
                                reversalInfoDao.create(reversalInfo);
                            }
                            else{
                                int retryNo = reversalInfo.getRetryNo();
                                if(retryNo > 0)
                                    isRepeat = true;
                                reversalInfoDao.updateRetryByRetRefNo(retRefNo, retryNo);
                            }
                            transInfoDao.updateCompletionStatusByRetRefNo(retRefNo, 1);
                            String responseCode = NIBSSRequests.doPurchaseReversal(reversalInfo,  isRepeat);
                            reversalInfo.setStatus(StringUtil.isEmpty(responseCode) ? "" : responseCode);
                            reversalInfo.setCompleted(1);
                            reversalInfoDao.updateStatusCompletionByRetRefNo(reversalInfo.getRetRefNo(), reversalInfo.getStatus(), reversalInfo.getCompleted());
                            transInfo.setReversed(1);
                            transInfo.setCompleted(1);
                            transInfoDao.updateReversalCompletionStatusByRetRefNo(transInfo.getRetRefNo(), transInfo.getReversed(), transInfo.getCompleted());
                        }
                        catch(Exception ex){
                            ex.printStackTrace();
                        }
                    }
                }).start();
            }else{
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try{
                            // Request never got to server, mark transaction as complete
                            transInfo.setCompleted(1);
                            transInfoDao.updateCompletionStatusByRetRefNo(transInfo.getRetRefNo(), transInfo.getCompleted());
                        }
                        catch(Exception ex){
                            ex.printStackTrace();
                        }
                    }
                }).start();
            }
            baseHandler.sendEmptyMessage(MSG_START_PRINT);
        }
    }
    private void doPurchaseResponse(byte[] respData){
        try{
            IsoMessage isoMsgResponse = IsoMessageUtil.getInstance().decode(respData);
            System.out.println(isoMsgResponse.debugString());
            String responseCode = isoMsgResponse.getObjectValue(39);
            transInfo.setStatus(StringUtil.isEmpty(responseCode) ? "96" : responseCode);
            String authNum = isoMsgResponse.getObjectValue(38);
            transInfo.setAuthNum(StringUtil.isEmpty(authNum) ? "" : authNum);
            transInfo.setCompleted(1);
            transInfoDao.updateStatusAuthNumCompletedByRetRefNo(transInfo.getRetRefNo(), transInfo.getStatus(), transInfo.getAuthNum(), transInfo.getCompleted());
            baseHandler.sendEmptyMessage(MSG_START_PRINT);
        }
        catch(Exception ex){
            ex.printStackTrace();
        }
    }



    //The plug-in to display the status of transaction
    private void setTransactionStatusActionTip(String info) {
        tranStatusText.setText(info);
    }

    private void setInfoActionTip(String tip) {
        infoText.setText(tip);
    }

    private void hideTransactionStatusActionTip() {
        tranStatusText.setText("");
    }

    //@Override
    /*protected void onBack() {
        finishAndReturnMainActivity();
    }
    */

    private void finishAndReturnMainActivity() {
        finishAppActivity();
        finish();
    }


    //Exit the App call method
    public void finishAppActivity() {
        for (int i = 0; i < WPOSApplication.activityList.size(); i++) {
            Activity ac = WPOSApplication.activityList.get(i);
            if (ac != null) {
                ac.finish();
            }
            ac = null;
        }
        WPOSApplication.activityList.clear();
        //android.os.Process.killProcess(android.os.Process.myPid());
        //System.exit(0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBankCard != null) {
            try {
                mBankCard.breakOffCommand();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }


    // do reversal
    //  ensure call home works
    // ensure periodic key download tsk decryption works

}
