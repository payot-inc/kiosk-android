package com.heykyul.linksample;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

public class MainActivity extends Activity implements ECAReceiverListener{
    public TableRow tableRow_tranno;
    public TableRow tableRow_trantype;
    public TableRow tableRow_terminaltype;
    public TableRow tableRow_cashamount;
    public TableRow tableRow_totalamount;
    public TableRow tableRow_tax;
    public TableRow tableRow_tip;
    public TableRow tableRow_installment;
    public TableRow tableRow_shoptid;
    public TableRow tableRow_shopbiznum;
    public TableRow tableRow_approvalnum;
    public TableRow tableRow_approvaldate;
    public TableRow tableRow_timeout;
    public TableRow tableRow_extensionoption;
    public TableRow tableRow_transerialno;
    public TableRow tableRow_addfield;
    public TableRow tableRow_printport;
    public TableRow tableRow_barcodedata;

    public TextView textView_tranno;
    public TextView textView_trantype;
    public TextView textView_terminaltype;
    public TextView textView_cashamount;
    public TextView textView_totalamount;
    public TextView textView_tax;
    public TextView textView_tip;
    public TextView textView_installment;
    public TextView textView_shoptid;
    public TextView textView_shopbiznum;
    public TextView textView_approvalnum;
    public TextView textView_approvaldate;
    public TextView textView_timeout;
    public TextView textView_extensionoption;
    public TextView textView_transerialno;
    public TextView textView_addfield;
    public TextView textView_res;
    public TextView textView_event;

    public EditText editText_tranno;
    public EditText editText_trantype;
    public EditText editText_terminaltype;
    public EditText editText_cashamount;
    public EditText editText_totalamount;
    public EditText editText_tax;
    public EditText editText_tip;
    public EditText editText_installment;
    public EditText editText_shoptid;
    public EditText editText_shopbiznum;
    public EditText editText_approvalnum;
    public EditText editText_approvaldate;
    public EditText editText_timeout;
    public EditText editText_extensionoption;
    public EditText editText_transerialno;
    public EditText editText_addfield;
    public EditText editText_printport;
    public EditText editText_barcodedata;
    public EditText editText_acktime;
    public EditText editText_makercode;
    public CheckBox checkbox_testbarcode, checkbox_ackflag;
    public Button call_easycarda;
    public Button broadcast_easycarda;
    public Button call_init;
    ArrayAdapter<CharSequence>  adspin;
    public String tran_type = "";

    private MakePrintMessage printMessage;
    private ECAReceiver receiver;

    final static public String EasyCardReqAction = "kr.co.kicc.easycarda.ACTION_REQ_BROADCAST"; //POS->EASYCARDA SendBroadcast
    final static public String EasyCardResAction = "kr.co.kicc.easycarda.broadcast";

    private UIHandler uiHandler = null;
    private DialogWhite dialogWhite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dialogWhite = new DialogWhite(this);
        uiHandler = new UIHandler(this);

        tableRow_tranno = (TableRow) findViewById(R.id.tableRow_tranno);
        tableRow_trantype = (TableRow) findViewById(R.id.tableRow_trantype);
        tableRow_terminaltype = (TableRow) findViewById(R.id.tableRow_terminaltype);
        tableRow_cashamount = (TableRow) findViewById(R.id.tableRow_cashamount);
        tableRow_totalamount = (TableRow) findViewById(R.id.tableRow_totalamount);
        tableRow_tax = (TableRow) findViewById(R.id.tableRow_tax);
        tableRow_tip = (TableRow) findViewById(R.id.tableRow_tip);
        tableRow_installment = (TableRow) findViewById(R.id.tableRow_installment);
        tableRow_shoptid = (TableRow) findViewById(R.id.tableRow_shoptid);
        tableRow_shopbiznum = (TableRow) findViewById(R.id.tableRow_shopbiznum);
        tableRow_approvalnum = (TableRow) findViewById(R.id.tableRow_approvalnum);
        tableRow_approvaldate = (TableRow) findViewById(R.id.tableRow_approvaldate);
        tableRow_timeout = (TableRow) findViewById(R.id.tableRow_timeout);
        tableRow_extensionoption = (TableRow) findViewById(R.id.tableRow_extensionoption);
        tableRow_transerialno = (TableRow) findViewById(R.id.tableRow_transerialno);
        tableRow_addfield = (TableRow) findViewById(R.id.tableRow_addfield);
        tableRow_printport = (TableRow) findViewById(R.id.tableRow_printport);
        tableRow_barcodedata = (TableRow) findViewById(R.id.tableRow_barcodedata);

        textView_tranno = (TextView) findViewById(R.id.textView_tranno);
        textView_trantype = (TextView) findViewById(R.id.textView_trantype);
        textView_terminaltype = (TextView) findViewById(R.id.textView_terminaltype);
        textView_cashamount = (TextView) findViewById(R.id.textView_cashamount);
        textView_totalamount = (TextView) findViewById(R.id.textView_totalamount);
        textView_tax = (TextView) findViewById(R.id.textView_tax);
        textView_tip = (TextView) findViewById(R.id.textView_tip);
        textView_installment = (TextView) findViewById(R.id.textView_installment);
        textView_shoptid = (TextView) findViewById(R.id.textView_shoptid);
        textView_shopbiznum = (TextView) findViewById(R.id.textView_shopbiznum);
        textView_approvalnum = (TextView) findViewById(R.id.textView_approvalnum);
        textView_approvaldate = (TextView) findViewById(R.id.textView_approvaldate);
        textView_timeout = (TextView) findViewById(R.id.textView_timeout);
        textView_extensionoption = (TextView) findViewById(R.id.textView_extensionoption);
        textView_transerialno = (TextView) findViewById(R.id.textView_transerialno);
        textView_addfield = (TextView) findViewById(R.id.textView_addfield);

        editText_tranno = (EditText) findViewById(R.id.editText_tranno);
        Spinner spinner = (Spinner) findViewById(R.id.spinner01);
        editText_terminaltype = (EditText) findViewById(R.id.editText_terminaltype);
        editText_cashamount = (EditText) findViewById(R.id.editText_cashamount);
        editText_totalamount = (EditText) findViewById(R.id.editText_totalamount);
        editText_tax = (EditText) findViewById(R.id.editText_tax);
        editText_tip = (EditText) findViewById(R.id.editText_tip);
        editText_installment = (EditText) findViewById(R.id.editText_installment);
        editText_shoptid = (EditText) findViewById(R.id.editText_shoptid);
        editText_shopbiznum = (EditText) findViewById(R.id.editText_shopbiznum);
        editText_approvalnum = (EditText) findViewById(R.id.editText_approvalnum);
        editText_approvaldate = (EditText) findViewById(R.id.editText_approvaldate);
        editText_timeout = (EditText) findViewById(R.id.editText_timeout);
        editText_extensionoption = (EditText) findViewById(R.id.editText_extensionoption);
        editText_transerialno = (EditText) findViewById(R.id.editText_transerialno);
        editText_addfield = (EditText) findViewById(R.id.editText_addfield);
        editText_printport = (EditText) findViewById(R.id.editText_printport);
        editText_barcodedata = (EditText) findViewById(R.id.editText_barcodedata);
        editText_makercode = (EditText) findViewById(R.id.editText_makercode);
        editText_acktime = (EditText) findViewById(R.id.editText_acktime);
        checkbox_testbarcode = (CheckBox) findViewById(R.id.checkbox_testbarcode);
        checkbox_ackflag = (CheckBox) findViewById(R.id.checkbox_ackflag);
        call_easycarda = (Button) findViewById(R.id.call_easycarda);
        textView_res = (TextView) findViewById(R.id.textView_res);
        textView_event = (TextView) findViewById(R.id.textView_event);
        broadcast_easycarda = (Button) findViewById(R.id.broadcast_easycarda);
        call_init = (Button) findViewById(R.id.call_init);

        adspin = ArrayAdapter.createFromResource(this, R.array.selected, android.R.layout.simple_spinner_item);
        adspin.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adspin);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                tran_type = adspin.getItem(position).toString();

                switch(adspin.getItem(position).toString()) {
                    case "PT":
                        tableRow_printport.setVisibility(View.VISIBLE);
                        tableRow_approvalnum.setVisibility(View.GONE);
                        tableRow_approvaldate.setVisibility(View.GONE);
                        tableRow_approvalnum.setVisibility(View.GONE);
                        tableRow_transerialno.setVisibility(View.GONE);
                        tableRow_terminaltype.setVisibility(View.GONE);
                        tableRow_barcodedata.setVisibility(View.GONE);
                        break;
                    case "TR":
                        tableRow_approvalnum.setVisibility(View.GONE);
                        tableRow_approvaldate.setVisibility(View.GONE);
                        tableRow_approvalnum.setVisibility(View.GONE);
                        tableRow_transerialno.setVisibility(View.GONE);
                        tableRow_printport.setVisibility(View.GONE);
                        tableRow_terminaltype.setVisibility(View.VISIBLE);
                        tableRow_barcodedata.setVisibility(View.GONE);
                        editText_terminaltype.setText("TK");
                        textView_totalamount.setText("토큰유효기간");
                        break;
                    case "D4":
                    case "B2":
                    case "B4":
                    case "S4":
                    case "M4":
                    case "Z2":
                        tableRow_approvalnum.setVisibility(View.VISIBLE);
                        tableRow_approvaldate.setVisibility(View.VISIBLE);
                        tableRow_approvalnum.setVisibility(View.VISIBLE);
                        tableRow_transerialno.setVisibility(View.VISIBLE);
                        tableRow_printport.setVisibility(View.GONE);
                        tableRow_terminaltype.setVisibility(View.VISIBLE);
                        tableRow_barcodedata.setVisibility(View.GONE);
                        editText_terminaltype.setText("40");
                        textView_totalamount.setText("총 금액");
                        break;
                    default :
                        tableRow_approvalnum.setVisibility(View.GONE);
                        tableRow_approvaldate.setVisibility(View.GONE);
                        tableRow_approvalnum.setVisibility(View.GONE);
                        tableRow_transerialno.setVisibility(View.GONE);
                        tableRow_printport.setVisibility(View.GONE);
                        tableRow_terminaltype.setVisibility(View.VISIBLE);
                        tableRow_barcodedata.setVisibility(View.VISIBLE);
                        editText_terminaltype.setText("40");
                        textView_totalamount.setText("총 금액");
                        break;
                }
            }

            public void onNothingSelected(AdapterView<?> arg0) {
                // TODO Auto-generated method stub
            }

        });

        checkbox_testbarcode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked){
                    editText_barcodedata.setText("hQVDUFYwMWFvTwfUEAAAAUAQVxOSAHcAaYZxItJAhgEJAACHAAAPXzQBAp9gEk5QBaNiGQCNS6G32u7JxUMrr2M2nyYIkfJdbN1sGlyfJwGAnxAUsSGgAAgAAAAAAAAAICQHJhMERwGfNgIAV4ICAACfNwQXv95C");
                } else {
                    editText_barcodedata.setText("");
                }
            }
        });
        call_easycarda.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v){
                setTranData(tran_type,false);
            }
        });

        broadcast_easycarda.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v){
                setTranData(tran_type,true);
            }
        });

        call_init.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v){
                setTranData("INIT",true);
            }
        });


        receiver = new ECAReceiver();
        receiver.setOnListener(MainActivity.this);

        IntentFilter filter = new IntentFilter(EasyCardResAction);
        this.registerReceiver(receiver, filter);
    }

    protected void onResume(){
        super.onResume();
        Log.e("onResume","onResume()");

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.unregisterReceiver(receiver);
    }

    public void setTranData(String tran_types, boolean broadcast_flag) {
        switch(tran_types) {
            case "B1":
            case "B3":
                if(this.editText_cashamount.getText().toString() == null || this.editText_cashamount.getText().toString().length() != 2) {
                    Toast.makeText(this, "현금영수증 거래용도 확인요망",Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
            case "B2":
            case "B4":
                if(this.editText_cashamount.getText().toString() == null || this.editText_cashamount.getText().toString().length() != 2) {
                    Toast.makeText(this, "현금영수증 거래용도 확인요망",Toast.LENGTH_SHORT).show();
                    return;
                }
                if(this.editText_approvalnum.getText().toString() == null || this.editText_approvalnum.getText().toString().length() < 5) {
                    Toast.makeText(this, "원승인번호 확인요망",Toast.LENGTH_SHORT).show();
                    return;
                }
                if(this.editText_approvaldate.getText().toString() == null || this.editText_approvaldate.getText().toString().length() < 6) {
                    Toast.makeText(this, "원승인일자 확인요망",Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
            case "D4":
            case "M4":
            case "S4":
            case "Z2":
                if(this.editText_approvalnum.getText().toString() == null || this.editText_approvalnum.getText().toString().length() < 5) {
                    Toast.makeText(this, "원승인번호 확인요망",Toast.LENGTH_SHORT).show();
                    return;
                }
                if(this.editText_approvaldate.getText().toString() == null || this.editText_approvaldate.getText().toString().length() < 6) {
                    Toast.makeText(this, "원승인일자 확인요망",Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
            case "D1":
                if(this.editText_terminaltype.getText().toString() == null || this.editText_terminaltype.getText().toString().length() < 2) {
                    editText_terminaltype.setText("40");
                }
                break;
        }

        Intent intent;
        if(!broadcast_flag){
            intent = new Intent(Intent.ACTION_MAIN);
        } else {
            intent = new Intent(EasyCardReqAction);
        }


        intent.putExtra("TRAN_NO", this.editText_tranno.getText().toString());
        intent.putExtra("TRAN_TYPE", tran_types);

        if(!"PT".equals(tran_types) && !"VERSION".equals(tran_types) && !"UPDATE".equals(tran_types) && !"INIT".equals(tran_types)){
            intent.putExtra("TERMINAL_TYPE", editText_terminaltype.getText().toString());
            intent.putExtra("TOTAL_AMOUNT",this.editText_totalamount.getText().toString());
            intent.putExtra("TAX",this.editText_tax.getText().toString());
            //intent.putExtra("TAX_OPTION","A");
            intent.putExtra("TIP",this.editText_tip.getText().toString());
            intent.putExtra("CASHAMOUNT",this.editText_cashamount.getText().toString());
            intent.putExtra("TEXT_SIGN", "Signature");

            intent.putExtra("EXTENSION_OPTION",this.editText_extensionoption.getText().toString());
            intent.putExtra("ADD_FIELD",this.editText_addfield.getText().toString());
            intent.putExtra("TIMEOUT",this.editText_timeout.getText().toString());
            intent.putExtra("TEXT_PROCESS","결제 진행중입니다");
            intent.putExtra("TEXT_COMPLETE", "결제가 완료되었습니다");
            intent.putExtra("MAKER_CODE",this.editText_makercode.getText().toString());
        }
        if("D4".equals(tran_types)|| "B2".equals(tran_types) || "B4".equals(tran_types)|| "S4".equals(tran_types) || "M4".equals(tran_types) || "Z2".equals(tran_types)) {
            intent.putExtra("APPROVAL_NUM", this.editText_approvalnum.getText().toString());
            intent.putExtra("APPROVAL_DATE", this.editText_approvaldate.getText().toString());
            intent.putExtra("TRAN_SERIALNO", this.editText_transerialno.getText().toString());
        }
        if("D1".equals(tran_types)||"D4".equals(tran_types)){
            intent.putExtra("INSTALLMENT", this.editText_installment.getText().toString());
        }

        if(this.editText_shoptid.getText().toString() != null && this.editText_shoptid.getText().toString().length() >1){
            intent.putExtra("SHOP_TID",this.editText_shoptid.getText().toString());
            intent.putExtra("SHOP_BIZ_NUM",this.editText_shopbiznum.getText().toString());
        }
        if(this.editText_barcodedata.getText().toString() != null && this.editText_barcodedata.getText().toString().length() >1){
            intent.putExtra("BARCODE_DATA",this.editText_barcodedata.getText().toString());
        }

        if("F3".equals(tran_types)){
            intent.putExtra("TRAN_TYPE","F3");
            intent.putExtra("TRAN_NO","1");
            intent.putExtra("OPTION_FIELD","1168119948;02;0788888;98765432;");
        }
        if("SL".equals(tran_types)){
            intent.putExtra("TRAN_TYPE","SL");
            intent.putExtra("TRAN_NO","1");
        }

        if("PT".equals(tran_types)){
            String signdata = "$1d7630331000400000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000780000000000000000000000000000007e0000000000000000000000000000007f00000000000000000000000000000077c0000000000000000000000000000071e0000000000000000000000000000070f0000000000000000000000000000070780000000000000000000000000000703c0000000000000000000000000000701e0000000000000000000000000000300f00000000000000000000000000003007000000000000000000000000000030038ffe0000000000000000000000003003ffff0000000000000000000000003001fe07000000000000000000000000300ff007000000000000000000000000303fe00f00000000000000000000000031fce01e00000000000000000000000037f0703c0000000000000000000000007fc070f8000000000000000000000000fe0039e000000000000000000000000ff8003fc000000000000000000000007ff0001f800000000000000000000003fe70001f000000000000000000000007f070007f000000000000000000000007c07000fb800000000000000000000001f07003e3c00000000000000000000000fe7007c1e000000000000000000000003ff00f00f0000000000000000000000007fc3e0070000000000000000000000000fff800380000000000000000000000007ffc003c00000000000000000000000073ffc01e000000000000000000000000779ff80e0000000000000000000000007f01ff870000000000000000000000007e001fff800000000000000000000000780003ff800000000000000000000000f800003f800000000000000000000001f8000003000000000000000000000003d0000000000000000000000000000003800000000000000000000000000000070000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000";
            byte[] printmsg = printMessage.receiptPrint("160516120000", "IC신용구매", false, false, 1004, 91, 0, 0, "1234-5678-****-123*", "테스트점", "1234567890", "홍길동", "02-1234-5678", "1234567", "서울 테스트구 테스트동", "발급사", "00001111", "", "", "12345678", "매입사", "", "", signdata);
            intent.putExtra("PRINT_DATA", printmsg);
            intent.putExtra("PRINT_PORT", this.editText_printport.getText().toString());
        }

        if("VERSION".equals(tran_types) || "UPDATE".equals(tran_types)|| "INIT".equals(tran_types) || "DL".equals(tran_types)){
            //TRANTYPE 말고 보낼게 없네
        }
        if(checkbox_ackflag.isChecked()){
            intent.putExtra("ACK_FLAG","Y");
        }
        if("CMD".equals(tran_types)){
            intent.putExtra("CMD", "FB");
            intent.putExtra("GCD", "11");
            intent.putExtra("JCD", "02");
            intent.putExtra("REQ_DATA", "");
//            //1초 내 응답이 없으면 리더기 미연결로 간주
//            uiHandler.sendEmptyMessageDelayed(0, 5000);
        }

        textView_res.setText("");

        if(!broadcast_flag){
            ComponentName compName = new ComponentName("kr.co.kicc.easycarda", "kr.co.kicc.easycarda.CallPopup");
            intent.setComponent(compName);
            startActivityForResult(intent, 1);
        } else {
            sendBroadcast(intent);
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            if (requestCode == 1) {

                if("D1".equals(data.getStringExtra("TRAN_TYPE")) || "B1".equals(data.getStringExtra("TRAN_TYPE")) || "B3".equals(data.getStringExtra("TRAN_TYPE"))) {
                    if("0000".equals(data.getStringExtra("RESULT_CODE"))){
                        editText_installment.setText(data.getStringExtra("INSTALLMENT"));
                        editText_approvalnum.setText(data.getStringExtra("APPROVAL_NUM"));
                        editText_transerialno.setText(data.getStringExtra("TRAN_SERIALNO"));
                        if (data.getStringExtra("APPROVAL_DATE") != null && data.getStringExtra("APPROVAL_DATE").length() > 6) {
                            editText_approvaldate.setText(data.getStringExtra("APPROVAL_DATE").substring(0, 6));
                        } else {
                            editText_approvaldate.setText(data.getStringExtra("APPROVAL_DATE"));
                        }
                    }
                }
                if("BC".equals(data.getStringExtra("TRAN_TYPE")) ) {
                    editText_barcodedata.setText(data.getStringExtra("CARD_NUM"));
                }


                String recv = "";
                if (data != null) {
                    Bundle extras = data.getExtras();
                    if (extras != null) {
                        Set keys = extras.keySet();
                        for (String _key : extras.keySet()) {
                            recv += "[" + _key + "] : " + extras.get(_key) + "\n";
                        }
                    }
                }

                if("SL".equals(data.getStringExtra("TRAN_TYPE"))){
                    int cnt =  Integer.parseInt(data.getStringExtra("SHOP_COUNT"));
                    String[] rtn_shop = data.getStringArrayExtra("SHOP_ARRAY");
                    for(int i = 0;i < cnt; i++){
                        recv += "[SHOP_ARRAY] : " + rtn_shop[i] + "\n";
                        //Log.e("heykyul", "["+i+"]"+rtn_shop[i]);
                    }
                    //i2.putExtra("SHOP_ARRAY", rtn_array);
                }
                textView_res.setText(recv);
            }
        }
    }

    public void GetIntent(Intent i) {
        uiHandler.removeMessages(0);
        String recv = "";
        if (i != null) {
            Bundle extras = i.getExtras();
            if (extras != null) {
                Set keys = extras.keySet();
                for (String _key : extras.keySet()) {
                    recv += "[" + _key + "] : " + extras.get(_key) + "\n";
                }

                if("SL".equals(i.getStringExtra("TRAN_TYPE"))){
                    int cnt =  Integer.parseInt(i.getStringExtra("SHOP_COUNT"));
                    String[] rtn_shop = i.getStringArrayExtra("SHOP_ARRAY");
                    for(int j = 0;j < cnt; j++){
                        recv += "[SHOP_ARRAY] : " + rtn_shop[j] + "\n";
                        //Log.e("heykyul", "["+j+"]"+rtn_shop[j]);
                    }
                    //i2.putExtra("SHOP_ARRAY", rtn_array);
                }

            }
        }

        if(i.getStringExtra("EVENT_CODE") != null && !"".equals(i.getStringExtra("EVENT_CODE")) ) {
            textView_event.setText(recv);
        } else {
            textView_res.setText(recv);
        }
        if("D1".equals(i.getStringExtra("TRAN_TYPE")) || "B1".equals(i.getStringExtra("TRAN_TYPE")) || "B3".equals(i.getStringExtra("TRAN_TYPE"))) {
            if("0000".equals(i.getStringExtra("RESULT_CODE"))){
                editText_installment.setText(i.getStringExtra("INSTALLMENT"));
                editText_approvalnum.setText(i.getStringExtra("APPROVAL_NUM"));
                editText_transerialno.setText(i.getStringExtra("TRAN_SERIALNO"));
                if (i.getStringExtra("APPROVAL_DATE") != null && i.getStringExtra("APPROVAL_DATE").length() > 6) {
                    editText_approvaldate.setText(i.getStringExtra("APPROVAL_DATE").substring(0, 6));
                } else {
                    editText_approvaldate.setText(i.getStringExtra("APPROVAL_DATE"));
                }
            }
        }
        if("BC".equals(i.getStringExtra("TRAN_TYPE")) ) {
            editText_barcodedata.setText(i.getStringExtra("CARD_NUM"));
        }
        if(checkbox_ackflag.isChecked()){
            Log.e("heykyul","SEND ACK");
            Intent ackIntent = new Intent(EasyCardReqAction);
            ackIntent.putExtra("TRAN_TYPE","ACK");
            int acktime = 1000; //1초
            try {
                acktime = Integer.parseInt(editText_acktime.getText().toString());
            }catch(Exception e){
                acktime = 0;
                editText_acktime.setText("0");
            }
            //sendBroadcast(ackIntent);
            Handler delayhandler = new Handler();
            delayhandler.postDelayed(
                    new Runnable() {
                        // 1 초 후에 실행
                        @Override
                        public void run() {
                            sendBroadcast(ackIntent);
                        }
                    }, acktime);

        }

    }


    class UIHandler extends Handler {

        private final WeakReference<MainActivity> mActivity;

        UIHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    dialogWhite.show();
                    dialogWhite.setCancelable(false);
                    dialogWhite.setDialogContents("리더기 연결을 확인해주세요.");
                    dialogWhite.setPositiveButton("확인", new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            dialogWhite.dismiss();
                        }
                    });
                    break;

            }
        }
    }

}