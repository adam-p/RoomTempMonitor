package ca.adampritchard.roomtempmonitor;

import android.Manifest;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.common.collect.EvictingQueue;
import com.google.common.collect.Ordering;

/**
 * Created by Adam on 2015-10-11.
 */

public class TemperatureRequester {
    Context mAppContext;
    Activity mActivity = null; // may be null
    ITempValuesCallback mCallback = null;

    GoogleAccountCredential mCredential;

    static final int REQUEST_ACCOUNT_PICKER = 1000;
    static final int REQUEST_AUTHORIZATION = 1001;
    static final int REQUEST_GOOGLE_PLAY_SERVICES = 1002;
    static final int PERMISSIONS_REQUEST_GET_ACCOUNTS = 1000;
    private static final String PREF_ACCOUNT_NAME = "accountName";
    private static final String[] SCOPES = { DriveScopes.DRIVE_READONLY };
    private static final String SHARED_PREF_NAME = "TemperatureRequester";

    /**
     * Constructor
     * @param appContext
     * @param activity optional; if null, no errors will be displayed
     */
    public TemperatureRequester(Context appContext, Activity activity) {
        mAppContext = appContext;
        mActivity = activity;

        SharedPreferences settings = mAppContext.getSharedPreferences(
                SHARED_PREF_NAME, Context.MODE_PRIVATE);

        // Initialize credentials and service object.
        mCredential = GoogleAccountCredential.usingOAuth2(
                mAppContext, Arrays.asList(SCOPES))
                .setBackOff(new ExponentialBackOff())
                .setSelectedAccountName(settings.getString(PREF_ACCOUNT_NAME, null));

    }

    /**
     * This callback will be called from the main thread.
     */
    public interface ITempValuesCallback {
        public void execute(boolean success, String errMsg,
                            Double currentTemperature,
                            Double minTemperature, Double maxTemperature);
    }

    public void getValues(ITempValuesCallback callback) {
        if (mCallback != null) {
            // There is an outstanding call
            return;
        }

        mCallback = callback;

        if (!isGooglePlayServicesAvailable()) {
            callbackErrorMessage("Google Play Services required: after installing, close and relaunch this app.");
        }

        refreshResults();
    }

    void callbackErrorMessage(String errMsg) {
        if (mCallback != null) {
            mCallback.execute(
                    false,
                    errMsg,
                    null, null, null);
            mCallback = null;
        }
    }

    /**
     * Check that Google Play services APK is installed and up to date. Will
     * launch an error dialog for the user to update Google Play Services if
     * possible.
     * @return true if Google Play Services is available and up to
     *     date on this device; false otherwise.
     */
    private boolean isGooglePlayServicesAvailable() {
        final int connectionStatusCode =
                GooglePlayServicesUtil.isGooglePlayServicesAvailable(mAppContext);
        if (GooglePlayServicesUtil.isUserRecoverableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode);
            return false;
        }
        else if (connectionStatusCode != ConnectionResult.SUCCESS) {
            return false;
        }
        return true;
    }

    /**
     * Display an error dialog showing that Google Play Services is missing
     * or out of date.
     * @param connectionStatusCode code describing the presence (or lack of)
     *     Google Play Services on this device.
     */
    void showGooglePlayServicesAvailabilityErrorDialog(
            final int connectionStatusCode) {
        if (mActivity != null) {
            Dialog dialog = GooglePlayServicesUtil.getErrorDialog(
                    connectionStatusCode,
                    mActivity,
                    REQUEST_GOOGLE_PLAY_SERVICES);
            dialog.show();
        }
    }

    public void activityResultRequestGooglePlayServices(
            int requestCode,
            int resultCode,
            Intent data) {
        if (resultCode != mActivity.RESULT_OK) {
            isGooglePlayServicesAvailable();
        }
    }

    public void activityResultRequestAccountPicker(
            int requestCode,
            int resultCode,
            Intent data) {
        if (resultCode == mActivity.RESULT_OK && data != null &&
                data.getExtras() != null) {
            String accountName =
                    data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (accountName != null) {
                mCredential.setSelectedAccountName(accountName);
                SharedPreferences settings = mAppContext.getSharedPreferences(
                        SHARED_PREF_NAME, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = settings.edit();
                editor.putString(PREF_ACCOUNT_NAME, accountName);
                editor.apply();
            }

            // Begin again...
            refreshResults();
        }
        else if (resultCode == mActivity.RESULT_CANCELED) {
            callbackErrorMessage("Account unspecified.");
        }
    }

    public void activityResultRequestAuthorization(
            int requestCode,
            int resultCode,
            Intent data) {
        if (resultCode != Activity.RESULT_OK) {
            chooseAccount();
        }
    }

    public void requestPermissionResultGetAccounts(
            int requestCode,
            String permissions[], int[] grantResults) {
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Okay to continue, so begin again...
            refreshResults();
        }
        else {
            callbackErrorMessage("Permission request denied");
        }
    }


    /**
     * Attempt to get a set of data from the Drive API to display. If the
     * email address isn't known yet, then call chooseAccount() method so the
     * user can pick an account.
     */
    private void refreshResults() {
        if (mCredential.getSelectedAccountName() == null) {
            chooseAccount();
        } else {
            if (isDeviceOnline()) {
                new MakeRequestTask(mCredential).execute();
            } else {
                callbackErrorMessage("No network connection available.");
            }
        }
    }

    /**
     * Starts an activity in Google Play Services so the user can pick an
     * account.
     */
    private void chooseAccount() {
        if (mActivity != null) {
            // Assume thisActivity is the current activity
            int permissionCheck = ContextCompat.checkSelfPermission(
                    mActivity,
                    Manifest.permission.GET_ACCOUNTS);

            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                // Skipping the explanation step

                ActivityCompat.requestPermissions(mActivity,
                        new String[]{Manifest.permission.GET_ACCOUNTS},
                        PERMISSIONS_REQUEST_GET_ACCOUNTS);

                // We need to wait for the result of the permissions request before proceeding.
                return;
            }

            mActivity.startActivityForResult(
                    mCredential.newChooseAccountIntent(),
                    REQUEST_ACCOUNT_PICKER);
        }
    }

    /**
     * Checks whether the device currently has a network connection.
     * @return true if the device has a network connection, false otherwise.
     */
    private boolean isDeviceOnline() {
        ConnectivityManager connMgr =
                (ConnectivityManager) mAppContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return (networkInfo != null && networkInfo.isConnected());
    }

    /**
     * An asynchronous task that handles the Drive API call.
     * Placing the API calls in their own task ensures the UI stays responsive.
     */
    private class MakeRequestTask extends AsyncTask<Void, Void, List<Double>> {
        private com.google.api.services.drive.Drive mService = null;
        private Exception mLastError = null;

        public MakeRequestTask(GoogleAccountCredential credential) {
            HttpTransport transport = AndroidHttp.newCompatibleTransport();
            JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
            mService = new com.google.api.services.drive.Drive.Builder(
                    transport, jsonFactory, credential)
                    .setApplicationName("room-temp-monitor")
                    .build();
        }

        /**
         * Background task to call Drive API.
         * @param params no parameters needed for this task.
         */
        @Override
        protected List<Double> doInBackground(Void... params) {
            try {
                return getDataFromApi();
            } catch (Exception e) {
                mLastError = e;
                cancel(true);
                return null;
            }
        }

        /**
         * Fetch a list of up to 10 file names and IDs.
         * @return List of Strings describing files, or an empty list if no files
         *         found.
         * @throws IOException
         */
        private List<Double> getDataFromApi() throws IOException {
            // We take readings every half hour, so keep 48 of them for 24 hours
            EvictingQueue<Double> sheetData = EvictingQueue.create(48);

            // Search for the exact title
            FileList result = mService.files().list()
                    .setQ("title = 'RaspberryPi_Temp_Humidity'")
                    .setMaxResults(1)
                    .execute();
            List<File> files = result.getItems();

            if (files == null || files.size() == 0) {
                mLastError = new Exception("Document with target title not found");
                return null;
            }

            String fileId = files.get(0).getId();

            if (fileId == null) {
                mLastError = new Exception("Document has null fileId");
                return null;
            }

            File file = mService.files().get(fileId).execute();

            String exportLink = file.getExportLinks().get("text/csv");

            if ((exportLink != null) && (exportLink.length() > 0)) {
                try {
                    HttpResponse resp =
                            mService.getRequestFactory()
                                    .buildGetRequest(new GenericUrl(exportLink))
                                    .execute();
                    InputStream inputstream = resp.getContent();

                    class BufInfo {
                        byte[] mBuf;
                        int mBufSize;

                        BufInfo(byte[] buf, int bufSize) {
                            mBuf = buf;
                            mBufSize = bufSize;
                        }
                    }

                    EvictingQueue<BufInfo> bufs = EvictingQueue.create(50);

                    byte[] buf = new byte[1024];
                    int bytesRead = inputstream.read(buf);

                    while(bytesRead != -1) {
                        bufs.add(new BufInfo(buf, bytesRead));

                        buf = new byte[1024];
                        bytesRead = inputstream.read(buf);
                    }

                    inputstream.close();

                    String data = "";

                    for (BufInfo bufInfo : bufs) {
                        data += new String(bufInfo.mBuf, 0, bufInfo.mBufSize);
                    }

                    if (data.length() == 0) {
                        mLastError = new Exception("No data read from document");
                        return null;
                    }

                    String[] lines = data.split("\r\n");
                    if (lines.length <= 1) {
                        mLastError = new Exception("Document data malformed (could not be split into lines)");
                        return null;
                    }

                    // Discard the first line, as it is either incomplete or the header line
                    for (int i = 1; i < lines.length; i++) {
                        String line = lines[i];
                        String[] values = line.split(",");

                        if (values.length != 3) {
                            continue;
                        }

                        Double temperature = null;
                        try {
                            temperature = Double.parseDouble(values[1]);
                        }
                        catch (NumberFormatException e) {
                            // pass
                            continue;
                        }

                        if (temperature != null) {
                            sheetData.add(temperature);
                        }
                    }

                    if (sheetData.size() == 0) {
                        mLastError = new Exception("Document contains no valid data");
                        return null;
                    }
                }
                catch (IOException e) {
                    // An error occurred.
                    e.printStackTrace();
                    mLastError = e;
                    return null;
                }
            }
            else {
                // The file doesn't have any content stored on Drive.
                mLastError = new Exception("Document has no export link");
                return null;
            }

            // At this point we know we have data to process

            Double max = Ordering.<Double> natural().max(sheetData.iterator());
            Double min = Ordering.<Double> natural().min(sheetData.iterator());

            Double[] sheetDataArray = new Double[sheetData.size()];
            sheetData.toArray(sheetDataArray);

            Double recent = sheetDataArray[sheetDataArray.length-1];

            List<Double> results = new ArrayList<>();

            results.add(recent);
            results.add(min);
            results.add(max);

            return results;
        }

        @Override
        protected void onPreExecute() {
            //mOutputText.setText("");
            //mProgress.show();
        }

        @Override
        protected void onPostExecute(List<Double> output) {
            if (mCallback != null) {
                assert(output.size() == 3);
                mCallback.execute(true, null, output.get(0), output.get(1), output.get(2));
                mCallback = null;
            }

            //mProgress.hide();
            /*
            if (output == null || output.size() == 0) {
                callbackErrorMessage("No results returned.");
            } else {
                output.add(0, "Data retrieved using the Drive API:");
                mOutputText.setText(TextUtils.join("\n", output));
            }
            */
        }

        @Override
        protected void onCancelled() {
            //mProgress.hide();
            if (mLastError != null) {
                if (mLastError instanceof GooglePlayServicesAvailabilityIOException) {
                    showGooglePlayServicesAvailabilityErrorDialog(
                            ((GooglePlayServicesAvailabilityIOException) mLastError)
                                    .getConnectionStatusCode());
                } else if (mLastError instanceof UserRecoverableAuthIOException) {
                    if (mActivity != null) {
                        mActivity.startActivityForResult(
                                ((UserRecoverableAuthIOException) mLastError).getIntent(),
                                REQUEST_AUTHORIZATION);
                    }
                } else {
                    callbackErrorMessage("The following error occurred:\n" + mLastError.getMessage());
                }
            } else {
                callbackErrorMessage("Request cancelled.");
            }
        }
    }
}
