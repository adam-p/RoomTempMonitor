package ca.adampritchard.roomtempmonitor;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView mOutputText;
    ProgressDialog mProgress;

    private TemperatureRequester mTemperatureRequester;

    /**
     * Create the main activity.
     * @param savedInstanceState previously saved instance data.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout activityLayout = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        activityLayout.setLayoutParams(lp);
        activityLayout.setOrientation(LinearLayout.VERTICAL);
        activityLayout.setPadding(16, 16, 16, 16);

        ViewGroup.LayoutParams tlp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        mOutputText = new TextView(this);
        mOutputText.setLayoutParams(tlp);
        mOutputText.setPadding(16, 16, 16, 16);
        mOutputText.setVerticalScrollBarEnabled(true);
        mOutputText.setMovementMethod(new ScrollingMovementMethod());
        activityLayout.addView(mOutputText);

        mProgress = new ProgressDialog(this);
        mProgress.setMessage("Calling Drive API ...");

        setContentView(activityLayout);

        mTemperatureRequester = new TemperatureRequester(
                getApplicationContext(), this);
    }


    /**
     * Called whenever this activity is pushed to the foreground, such as after
     * a call to onCreate().
     */
    @Override
    protected void onResume() {
        super.onResume();

        refreshResults();
    }

    /**
     * Called when an activity launched here (specifically, AccountPicker
     * and authorization) exits, giving you the requestCode you started it with,
     * the resultCode it returned, and any additional data from it.
     * @param requestCode code indicating which activity result is incoming.
     * @param resultCode code indicating the result of the incoming
     *     activity result.
     * @param data Intent (containing result data) returned by incoming
     *     activity result.
     */
    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == mTemperatureRequester.REQUEST_GOOGLE_PLAY_SERVICES) {
            mTemperatureRequester.activityResultRequestGooglePlayServices(
                    requestCode, resultCode, data);
        }
        else if (requestCode == mTemperatureRequester.REQUEST_ACCOUNT_PICKER) {
            mTemperatureRequester.activityResultRequestAccountPicker(
                    requestCode, resultCode, data);
        }
        else if (requestCode == mTemperatureRequester.REQUEST_AUTHORIZATION) {
            mTemperatureRequester.activityResultRequestAuthorization(
                    requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == mTemperatureRequester.PERMISSIONS_REQUEST_GET_ACCOUNTS) {
            mTemperatureRequester.requestPermissionResultGetAccounts(
                    requestCode, permissions, grantResults);
        }

    }

    private void refreshResults() {
        TemperatureRequester.ITempValuesCallback callback = new TemperatureRequester.ITempValuesCallback() {
            public void execute(boolean success, String errMsg,
                                Double currentTemperature,
                                Double minTemperature, Double maxTemperature) {
                if (success) {
                    mOutputText.setText(String.format("Now: %.1f°C\nMin: %.1f°C\nMax: %.1f°C", currentTemperature, minTemperature, maxTemperature));
                }
                else {
                    mOutputText.setText(errMsg);
                }
            }
        };

        mTemperatureRequester.getValues(callback);
    }

}
