# UC2- Flexible Replanning 

Digital Twin for the UC2 Flexible Replanning 

## Running the project

1. Copy `.env.example` to `.env` and fill in your InfluxDB credentials.

2. Start the supporting services (MQTT broker, InfluxDB): docker-compose up -d mqtt-broker influxdb

3. Build the app:
   ./mvnw clean compile
4. Run it: ./mvnw spring-boot:run