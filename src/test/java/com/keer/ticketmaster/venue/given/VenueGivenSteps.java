package com.keer.ticketmaster.venue.given;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.po.Venue;
import com.keer.ticketmaster.repository.VenueRepository;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.zh_tw.假如;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

public class VenueGivenSteps {

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private ScenarioContext scenarioContext;

    @假如("系統中沒有任何場館資料")
    public void 系統中沒有任何場館資料() {
        venueRepository.deleteAll();
    }

    @假如("^系統中已存在一個場館，名稱為「(.+)」，地址為「(.+)」，容量為 (\\d+)$")
    public void 系統中已存在一個場館_舊格式(String name, String address, int capacity) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setLocation(address);
        Venue saved = venueRepository.save(venue);
        scenarioContext.set("createdVenueId", saved.getId());
    }

    @假如("^系統中已存在一個場館，名稱為「(.+)」，地點為「(.+)」$")
    public void 系統中已存在一個場館(String name, String location) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setLocation(location);
        Venue saved = venueRepository.save(venue);
        scenarioContext.set("createdVenueId", saved.getId());
    }

    @假如("系統中已存在以下場館:")
    public void 系統中已存在以下場館(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            Venue venue = new Venue();
            venue.setName(row.get("name"));
            venue.setLocation(row.get("location") != null ? row.get("location") : row.get("address"));
            venueRepository.save(venue);
        }
    }
}
