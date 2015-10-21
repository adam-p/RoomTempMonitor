package ca.adampritchard.roomtempmonitor;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

public class RoomTempMonitorWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "RoomTempMonitorWidgetProvider";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        final Context fContext = context;
        final AppWidgetManager fAppWidgetManager = appWidgetManager;
        final int[] fAppWidgetIds = appWidgetIds;

        TemperatureRequester temperatureRequester = new TemperatureRequester(context, null);

        TemperatureRequester.ITempValuesCallback callback = new TemperatureRequester.ITempValuesCallback() {
            public void execute(boolean success, String errMsg,
                                Double currentTemperature,
                                Double minTemperature, Double maxTemperature) {
                if (success) {
                    final int N = fAppWidgetIds.length;

                    // Perform this loop procedure for each App Widget that belongs to this provider
                    for (int i=0; i<N; i++) {
                        int appWidgetId = fAppWidgetIds[i];

                        // Get the layout for the App Widget and attach an on-click listener
                        // to the button
                        RemoteViews views = new RemoteViews(fContext.getPackageName(), R.layout.widget);

                        // Set the text
                        views.setTextViewText(R.id.update, String.format("Now: %.1f°C\nMin: %.1f°C\nMax: %.1f°C", currentTemperature, minTemperature, maxTemperature));

                        // Tell the AppWidgetManager to perform an update on the current app widget
                        fAppWidgetManager.updateAppWidget(appWidgetId, views);
                    }
                }
                else {
                    //mOutputText.setText(errMsg);
                }
            }
        };

        temperatureRequester.getValues(callback);
    }

}
