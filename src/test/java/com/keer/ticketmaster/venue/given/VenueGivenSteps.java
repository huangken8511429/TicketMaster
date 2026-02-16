package com.keer.ticketmaster.venue.given;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.venue.model.Venue;
import com.keer.ticketmaster.venue.repository.VenueRepository;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.zh_tw.假如;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
    public void 系統中已存在一個場館(String name, String address, int capacity) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setAddress(address);
        venue.setCapacity(capacity);
        Venue saved = venueRepository.save(venue);
        // 將創建的場館 ID 存入 ScenarioContext 供 When 步驟使用
        scenarioContext.set("createdVenueId", saved.getId());
    }

    @假如("系統中已存在以下場館:")
    public void 系統中已存在以下場館(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            Venue venue = new Venue();
            venue.setName(row.get("name"));
            venue.setAddress(row.get("address"));
            venue.setCapacity(Integer.parseInt(row.get("capacity")));
            venueRepository.save(venue);
        }
    }
}
