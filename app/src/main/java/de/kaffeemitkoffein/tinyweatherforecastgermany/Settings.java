/**
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2020 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

public class Settings extends PreferenceActivity{

    SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
            Log.v("PREF:","ON SHARED PREF CHANGED CALLEd");
            updateValuesDisplay();
        }
    };

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate(Bundle bundle){
        super.onCreate(bundle);
        addPreferencesFromResource(R.xml.preferences);
        updateValuesDisplay();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onResume(){
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onPause(){
        super.onPause();
        if (listener!=null){
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(listener);
        }
    }

    @SuppressWarnings("deprecation")
    private void updateValuesDisplay(){
        SharedPreferences sp= PreferenceManager.getDefaultSharedPreferences(this);
        Preference p;
        String gadgetbridge_packagename = sp.getString(WeatherSettings.PREF_GADGETBRIDGE_PACKAGENAME,WeatherSettings.PREF_GADGETBRIDGE_PACKAGENAME_DEFAULT);
        if (gadgetbridge_packagename.equals("")){
            Log.v("PREF","UPDATING");
            gadgetbridge_packagename = WeatherSettings.PREF_GADGETBRIDGE_PACKAGENAME_DEFAULT;
            SharedPreferences.Editor preferences_editor = sp.edit();
            preferences_editor.putString(WeatherSettings.PREF_GADGETBRIDGE_PACKAGENAME, gadgetbridge_packagename);
            preferences_editor.commit();
            Toast.makeText(this,getResources().getString(R.string.preference_gadgetbridge_package_reset_toast),Toast.LENGTH_LONG).show();
            finish();
        }
     }

}
