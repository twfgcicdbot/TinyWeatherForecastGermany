package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.URL;
import java.util.Calendar;
import java.util.zip.ZipInputStream;

public class WeatherForecastReader extends AsyncTask<Void,Void, RawWeatherInfo> {

    private Context context;
    private Weather.WeatherLocation weatherLocation;

    private String[] seperateValues(String s){
        String[] resultarray = new String[Weather.DATA_SIZE];
        int index =0;
        while (String.valueOf(s.charAt(index)).equals(" ")){
            index++;
        }
        //Log.v("PPPP","Spaces:"+index);
        s=s.substring(index);
        //Log.v("PPPP",s);
        resultarray = s.split(" +");
        // String[] resultarray2 = new String[Weather.DATA_SIZE];
        return resultarray;
    }

    private String[] assigntoRaw(final Element element){
        String[] result = new String[Weather.DATA_SIZE];
        NodeList values = element.getElementsByTagName("dwd:value");
        for (int j=0; j<values.getLength(); j++){
            Element value_element = (Element) values.item(j);
            String value_string = value_element.getFirstChild().getNodeValue();
            result = seperateValues(value_string);
        }
        return result;
    }

    public WeatherForecastReader(Context context){
        this.context = context;
        WeatherSettings weatherSettings = new WeatherSettings(context);
        this.weatherLocation = weatherSettings.getSetStationLocation();
    }

    @Override
    protected RawWeatherInfo doInBackground(Void... voids) {
        String weather_url = "https://opendata.dwd.de/weather/local_forecasts/mos/MOSMIX_L/single_stations/"+weatherLocation.name+"/kml/MOSMIX_L_LATEST_"+weatherLocation.name+".kmz";
        try{
            Log.v("PPPP","Stating the Forecastreader........................");
            Log.v("PPPP","URL: "+weather_url);
            URL url = new URL(weather_url);
            ZipInputStream zipInputStream = new ZipInputStream(new URL(weather_url).openStream());
            zipInputStream.getNextEntry();
            // init new RawWeatherInfo instance to fill with data
            Log.v("PPPP","Init a rawWeatherInfo instance....");
            RawWeatherInfo rawWeatherInfo = new RawWeatherInfo();
            // rawWeatherInfo.description = weatherLocation.description;
            try {
                DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                Document document = documentBuilder.parse(zipInputStream);
            Log.v("PPPP","Building....");
            rawWeatherInfo.description = "?";
            Log.v("PPPP",rawWeatherInfo.description);
            // get sensor description, usually city name. This should be equal to weatherLocation.description,
            // but we take it from the api to be sure nothing changed and the right city gets displayed!

            NodeList placemark_nodes = document.getElementsByTagName("kml:description");
            for (int i=0;i<placemark_nodes.getLength(); i++){
                // should be only one, but we take the latest
                Element placemark_element = (Element) placemark_nodes.item(i);
                String description = placemark_element.getFirstChild().getNodeValue();
                Log.v("PPPP",description);
                rawWeatherInfo.description = description;
            }

            NodeList timesteps = document.getElementsByTagName("dwd:TimeStep");
            Log.v("PPPP","Getting timesteps....");
            for (int i=0; i<timesteps.getLength(); i++){
                Log.v("PPPP","Getting timesteps...."+i);
                Element element = (Element) timesteps.item(i);
                rawWeatherInfo.timesteps[i] = element.getFirstChild().getNodeValue();
                rawWeatherInfo.elements = i;
            }
            NodeList forecast = document.getElementsByTagName("dwd:Forecast");
            Log.v("PPPP","Getting forecast data....");
            Log.v("PPPP","Forecast length: "+forecast.getLength());
            for (int i=0; i<forecast.getLength(); i++){
                Element element = (Element) forecast.item(i);
                String type     = element.getAttribute("dwd:elementName");
               Log.v("PPPP","Type/Element Name (+"+i+"): "+type);
                switch (type){
                    case "TTT": rawWeatherInfo.TTT = assigntoRaw(element); break;
                    case "E_TTT": rawWeatherInfo.E_TTT = assigntoRaw(element); break;
                    case "T5cm": rawWeatherInfo.T5cm = assigntoRaw(element); break;
                    case "Td": rawWeatherInfo.Td = assigntoRaw(element); break;
                    case "E_Td": rawWeatherInfo.E_Td = assigntoRaw(element); break;
                    case "Tx": rawWeatherInfo.Tx = assigntoRaw(element); break;
                    case "Tn": rawWeatherInfo.Tn = assigntoRaw(element); break;
                    case "TM": rawWeatherInfo.TM = assigntoRaw(element); break;
                    case "TG": rawWeatherInfo.TG = assigntoRaw(element); break;
                    case "DD": rawWeatherInfo.DD = assigntoRaw(element); break;
                    case "E_DD": rawWeatherInfo.E_DD = assigntoRaw(element); break;
                    case "FF": rawWeatherInfo.FF = assigntoRaw(element); break;
                    case "E_FF": rawWeatherInfo.E_FF = assigntoRaw(element); break;
                    case "FX1": rawWeatherInfo.FX1 = assigntoRaw(element); break;
                    case "FX3": rawWeatherInfo.FX3 = assigntoRaw(element); break;
                    case "FXh": rawWeatherInfo.FXh = assigntoRaw(element); break;
                    case "FXh25": rawWeatherInfo.FXh25 = assigntoRaw(element); break;
                    case "FXh40": rawWeatherInfo.FXh40 = assigntoRaw(element); break;
                    case "FXh55": rawWeatherInfo.FXh55 = assigntoRaw(element); break;
                    case "FX625": rawWeatherInfo.FX625 = assigntoRaw(element); break;
                    case "FX640": rawWeatherInfo.FX640 = assigntoRaw(element); break;
                    case "FX655": rawWeatherInfo.FX655 = assigntoRaw(element); break;
                    case "RR1c": rawWeatherInfo.RR1c = assigntoRaw(element); break;
                    case "RRL1c": rawWeatherInfo.RRL1c = assigntoRaw(element); break;
                    case "RR3": rawWeatherInfo.RR3 = assigntoRaw(element); break;
                    case "RR6": rawWeatherInfo.RR6 = assigntoRaw(element); break;
                    case "RR3c": rawWeatherInfo.RR3c = assigntoRaw(element); break;
                    case "RR6c": rawWeatherInfo.RR6c = assigntoRaw(element); break;
                    case "RRhc": rawWeatherInfo.RRhc = assigntoRaw(element); break;
                    case "RRdc": rawWeatherInfo.RRdc = assigntoRaw(element); break;
                    case "RRS1c": rawWeatherInfo.RRS1c = assigntoRaw(element); break;
                    case "RRS3c": rawWeatherInfo.RRS3c = assigntoRaw(element); break;
                    case "R101": rawWeatherInfo.R101 = assigntoRaw(element); break;
                    case "R102": rawWeatherInfo.R102 = assigntoRaw(element); break;
                    case "R103": rawWeatherInfo.R103 = assigntoRaw(element); break;
                    case "R105": rawWeatherInfo.R105 = assigntoRaw(element); break;
                    case "R107": rawWeatherInfo.R107 = assigntoRaw(element); break;
                    case "R110": rawWeatherInfo.R110 = assigntoRaw(element); break;
                    case "R120": rawWeatherInfo.R120 = assigntoRaw(element); break;
                    case "R130": rawWeatherInfo.R130 = assigntoRaw(element); break;
                    case "R150": rawWeatherInfo.R150 = assigntoRaw(element); break;
                    case "RR1o1": rawWeatherInfo.RR1o1 = assigntoRaw(element); break;
                    case "RR1w1": rawWeatherInfo.RR1w1 = assigntoRaw(element); break;
                    case "RR1u1": rawWeatherInfo.RR1u1 = assigntoRaw(element); break;
                    case "R600": rawWeatherInfo.R600 = assigntoRaw(element); break;
                    case "Rh00": rawWeatherInfo.Rh00 = assigntoRaw(element); break;
                    case "R602": rawWeatherInfo.R602 = assigntoRaw(element); break;
                    case "Rh02": rawWeatherInfo.Rh02 = assigntoRaw(element); break;
                    case "Rd02": rawWeatherInfo.Rd02 = assigntoRaw(element); break;
                    case "R610": rawWeatherInfo.R610 = assigntoRaw(element); break;
                    case "Rh10": rawWeatherInfo.Rh10 = assigntoRaw(element); break;
                    case "R650": rawWeatherInfo.R650 = assigntoRaw(element); break;
                    case "Rh50": rawWeatherInfo.Rh50 = assigntoRaw(element); break;
                    case "Rd00": rawWeatherInfo.Rd00 = assigntoRaw(element); break;
                    case "Rd10": rawWeatherInfo.Rd10 = assigntoRaw(element); break;
                    case "Rd50": rawWeatherInfo.Rd50 = assigntoRaw(element); break;
                    case "wwPd": rawWeatherInfo.wwPd = assigntoRaw(element); break;
                    case "DRR1": rawWeatherInfo.DRR1 = assigntoRaw(element); break;
                    case "wwZ": rawWeatherInfo.wwZ = assigntoRaw(element); break;
                    case "wwZ6": rawWeatherInfo.wwZ6 = assigntoRaw(element); break;
                    case "wwZh": rawWeatherInfo.wwZh = assigntoRaw(element); break;
                    case "wwD": rawWeatherInfo.wwD = assigntoRaw(element); break;
                    case "wwD6": rawWeatherInfo.wwD6 = assigntoRaw(element); break;
                    case "wwDh": rawWeatherInfo.wwDh = assigntoRaw(element); break;
                    case "wwC": rawWeatherInfo.wwC = assigntoRaw(element); break;
                    case "wwC6": rawWeatherInfo.wwC6 = assigntoRaw(element); break;
                    case "wwCh": rawWeatherInfo.wwCh = assigntoRaw(element); break;
                    case "wwT": rawWeatherInfo.wwT = assigntoRaw(element); break;
                    case "wwT6": rawWeatherInfo.wwT6 = assigntoRaw(element); break;
                    case "wwTh": rawWeatherInfo.wwTh = assigntoRaw(element); break;
                    case "wwTd": rawWeatherInfo.wwTd = assigntoRaw(element); break;
                    case "wwL": rawWeatherInfo.wwL = assigntoRaw(element); break;
                    case "wwL6": rawWeatherInfo.wwL6 = assigntoRaw(element); break;
                    case "wwLh": rawWeatherInfo.wwLh = assigntoRaw(element); break;
                    case "wwS": rawWeatherInfo.wwS = assigntoRaw(element); break;
                    case "wwS6": rawWeatherInfo.wwS6 = assigntoRaw(element); break;
                    case "wwSh": rawWeatherInfo.wwSh = assigntoRaw(element); break;
                    case "wwF": rawWeatherInfo.wwF = assigntoRaw(element); break;
                    case "wwF6": rawWeatherInfo.wwF6 = assigntoRaw(element); break;
                    case "wwFh": rawWeatherInfo.wwFh = assigntoRaw(element); break;
                    case "wwP": rawWeatherInfo.wwP = assigntoRaw(element); break;
                    case "wwP6": rawWeatherInfo.wwP6 = assigntoRaw(element); break;
                    case "wwPh": rawWeatherInfo.wwPh = assigntoRaw(element); break;
                    case "VV10": rawWeatherInfo.VV10 = assigntoRaw(element); break;
                    case "ww": rawWeatherInfo.ww = assigntoRaw(element); break;
                    case "ww3": rawWeatherInfo.ww3 = assigntoRaw(element); break;
                    case "W1W2": rawWeatherInfo.W1W2 = assigntoRaw(element); break;
                    case "WPc31": rawWeatherInfo.WPc31 = assigntoRaw(element); break;
                    case "WPc61": rawWeatherInfo.WPc61 = assigntoRaw(element); break;
                    case "WPch1": rawWeatherInfo.WPch1 = assigntoRaw(element); break;
                    case "WPcd1": rawWeatherInfo.WPcd1 = assigntoRaw(element); break;
                    case "N": rawWeatherInfo.N = assigntoRaw(element); break;
                    case "N05": rawWeatherInfo.N05 = assigntoRaw(element); break;
                    case "Nl": rawWeatherInfo.Nl = assigntoRaw(element); break;
                    case "Nm": rawWeatherInfo.Nm = assigntoRaw(element); break;
                    case "Nh": rawWeatherInfo.Nh = assigntoRaw(element); break;
                    case "Nlm": rawWeatherInfo.Nlm = assigntoRaw(element); break;
                    case "H_BsC": rawWeatherInfo.H_BsC = assigntoRaw(element); break;
                    case "PPPP": rawWeatherInfo.PPPP = assigntoRaw(element); break;
                    case "E_PPP": rawWeatherInfo.E_PPP = assigntoRaw(element); break;
                    case "RadS3": rawWeatherInfo.RadS3 = assigntoRaw(element); break;
                    case "RRad1": rawWeatherInfo.RRad1 = assigntoRaw(element); break;
                    case "Rad1h": rawWeatherInfo.Rad1h = assigntoRaw(element); break;
                    case "RadL3": rawWeatherInfo.RadL3 = assigntoRaw(element); break;
                    case "VV": rawWeatherInfo.VV = assigntoRaw(element); break;
                    case "D1": rawWeatherInfo.D1 = assigntoRaw(element); break;
                    case "SunD": rawWeatherInfo.SunD = assigntoRaw(element); break;
                    case "SunD3": rawWeatherInfo.SunD3 = assigntoRaw(element); break;
                    case "RSunD": rawWeatherInfo.RSunD = assigntoRaw(element); break;
                    case "PSd00": rawWeatherInfo.PSd00 = assigntoRaw(element); break;
                    case "PSd30": rawWeatherInfo.PSd30 = assigntoRaw(element); break;
                    case "PSd60": rawWeatherInfo.PSd60 = assigntoRaw(element); break;
                    case "wwM": rawWeatherInfo.wwM = assigntoRaw(element); break;
                    case "wwM6": rawWeatherInfo.wwM6 = assigntoRaw(element); break;
                    case "wwMh": rawWeatherInfo.wwMh = assigntoRaw(element); break;
                    case "wwMd": rawWeatherInfo.wwMd = assigntoRaw(element); break;
                    case "PEvap": rawWeatherInfo.PEvap = assigntoRaw(element); break;
                }
            }
            PrivateLog.log(context,"Elements read: "+rawWeatherInfo.elements);
            return rawWeatherInfo;
            } catch (Exception e){
                Log.v("PPPP","Parsing error!");
            }
        } catch (IOException e){
            Log.v("TAG",e.toString());
        }
        return null;
    }

    public void onNegativeResult(){
        // do nothing at the moment.
        PrivateLog.log(context,"","FAILED GETTING DATA!!!");
    }

    /*
     * Override this routine to define what to do if obtaining data succeeded.
     * Remember: at this point, the new data is already written to the database and can be
     * accessed via the CardHandler class.
     */

    public void onPositiveResult(){
        // do nothing at the moment.
    }

    public void onPositiveResult(RawWeatherInfo rawWeatherInfo){
        onPositiveResult();

        for (int i=0;i< rawWeatherInfo.elements;i++){
            Log.v("PPPP",rawWeatherInfo.timesteps[i]+" PPPP:"+rawWeatherInfo.PPPP[i]+" TTT:"+rawWeatherInfo.TTT[i]+" ww:"+rawWeatherInfo.ww[i]);
        }

    }

    protected void onPostExecute(RawWeatherInfo rawWeatherInfo) {
        Log.v("PPPP","Postexecute reached.");
        if (rawWeatherInfo == null) {
            Log.v("PPPP","Postexecute reached, result null.");
            onNegativeResult();
        } else {
            // get timestamp
            Calendar calendar = Calendar.getInstance();
            rawWeatherInfo.polling_time = calendar.getTimeInMillis();
            // writes the weather data to the database
            Log.v("PPPP","Postexecute reached, result ok, writing.");
            WeatherForecastContentProvider weatherForecastContentProvider = new WeatherForecastContentProvider();
            weatherForecastContentProvider.writeWeatherForecast(context,rawWeatherInfo);
            onPositiveResult(rawWeatherInfo);
            Log.v("PPPP","Postexecute termiates as expected.");
        }
    }

}
