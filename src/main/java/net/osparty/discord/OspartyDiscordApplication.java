package net.osparty.discord;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OSParty Discord service. Owns the single JDA gateway connection and exposes a small internal REST API
 * (see {@code web/VoiceController}) that the horizontally-scaled osparty-api instances call to provision
 * and tear down per-party voice channels. Extracted from the API so the gateway connection is a singleton
 * regardless of how many API instances run.
 */
@SpringBootApplication
public class OspartyDiscordApplication {
	public static void main(String[] args) {
		SpringApplication.run(OspartyDiscordApplication.class, args);
	}
}
