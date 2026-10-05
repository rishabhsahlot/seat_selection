package com.paytm.seat_selection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SeatSelectionApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatSelectionApplication.class, args);
	}

}
