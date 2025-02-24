/*******************************************************************************
* Copyright (c) 2013 IBM Corp.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*******************************************************************************/

package com.acmeair.loader;

import com.acmeair.AirportCodeMapping;
import com.acmeair.service.FlightService;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

public class FlightLoader {
  
  @Inject
  FlightService flightService;
  
  @Inject 
  @ConfigProperty(name = "NUM_DAYS_TO_LOAD", defaultValue = "5") 
  private Short numDaysToLoad;

  private static Logger logger = Logger.getLogger(FlightLoader.class.getName());

  public String queryLoader() {
    return numDaysToLoad.toString();
  }
  
  /**
   * Loads the db with flight info.
   */
  public String loadFlightDb(int daysToLoad) {
    double length = 0;
    try {

      logger.info("Start loading flights");
      long start = System.currentTimeMillis();
      flightService.dropFlights();
      flightService.invalidateFlightDataCaches();
      loadFlights(daysToLoad);
      long stop = System.currentTimeMillis();
      logger.info("Finished loading in " + (stop - start) / 1000.0 + " seconds");
      length = (stop - start) / 1000.0;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "Loaded flights for " + daysToLoad + " days in " + length + " seconds";
  }

  public void dropFlights() {
    flightService.dropFlights();
  }


  /**
   * Loads the db with flights.
   */
  public void loadFlights(int daysToLoad) throws Exception {
    InputStream csvInputStream = FlightLoader.class.getResourceAsStream("/META-INF/mileage.csv");

    LineNumberReader lnr = new LineNumberReader(new InputStreamReader(csvInputStream));
    String line1 = lnr.readLine();
    StringTokenizer st = new StringTokenizer(line1, ",");
    ArrayList<AirportCodeMapping> airports = new ArrayList<AirportCodeMapping>();

    // read the first line which are airport names
    while (st.hasMoreTokens()) {
      AirportCodeMapping acm = flightService.createAirportCodeMapping(null, st.nextToken());
      airports.add(acm);
    }
    // read the second line which contains matching airport codes for the first line
    String line2 = lnr.readLine();
    st = new StringTokenizer(line2, ",");
    int ii = 0;
    while (st.hasMoreTokens()) {
      String airportCode = st.nextToken();
      airports.get(ii).setAirportCode(airportCode);
      ii++;
    }

    // read the other lines which are of format:
    // airport name, aiport code, distance from this airport to whatever 
    // airport is in the column from lines one and two
    String line;
    int flightNumber = 0;
    while (true) {
      line = lnr.readLine();
      if (line == null || line.trim().equals("")) {
        break;
      }
      
      st = new StringTokenizer(line, ",");
      String airportName = st.nextToken();
      String airportCode = st.nextToken();
      if (!alreadyInCollection(airportCode, airports)) {
        AirportCodeMapping acm = flightService.createAirportCodeMapping(airportCode, airportName);
        airports.add(acm);
      }
      int indexIntoTopLine = 0;
      while (st.hasMoreTokens()) {
        String milesString = st.nextToken();
        if (milesString.equals("NA")) {
          indexIntoTopLine++;
          continue;
        }
        int miles = Integer.parseInt(milesString);
        String toAirport = airports.get(indexIntoTopLine).getAirportCode();
        String flightId = "AA" + flightNumber;
        flightService.storeFlightSegment(flightId, airportCode, toAirport, miles);
        Date now = new Date();
                
        for (int daysFromNow = -1; daysFromNow < daysToLoad; daysFromNow++) {
          Calendar c = Calendar.getInstance();
          c.setTime(now);
          c.set(Calendar.HOUR_OF_DAY, 0);
          c.set(Calendar.MINUTE, 0);
          c.set(Calendar.SECOND, 0);
          c.set(Calendar.MILLISECOND, 0);
          c.add(Calendar.DATE, daysFromNow);
          Date departureTime = c.getTime();
          Date arrivalTime = getArrivalTime(departureTime, miles);
          flightService.createNewFlight(flightId, departureTime, arrivalTime, 500, 200, 10, 200, 
                  "B747");
        }

        flightNumber++;
        indexIntoTopLine++;
      }
    }
    
    for (int jj = 0; jj < airports.size(); jj++) {
      flightService.storeAirportMapping(airports.get(jj));
    }
    lnr.close();
  }
  
  private static Date getArrivalTime(Date departureTime, int mileage) {
    double averageSpeed = 600.0; // 600 miles/hours
    double hours = (double) mileage / averageSpeed; // miles / miles/hour = hours
    double partsOfHour = hours % 1.0;
    int minutes = (int)(60.0 * partsOfHour);
    Calendar c = Calendar.getInstance();
    c.setTime(departureTime);
    c.add(Calendar.HOUR, (int)hours);
    c.add(Calendar.MINUTE, minutes);
    return c.getTime();
  }

  private static boolean alreadyInCollection(String airportCode, 
          ArrayList<AirportCodeMapping> airports) {
    for (int ii = 0; ii < airports.size(); ii++) {
      if (airports.get(ii).getAirportCode().equals(airportCode)) {
        return true;
      }
    }
    return false;
  }
}
