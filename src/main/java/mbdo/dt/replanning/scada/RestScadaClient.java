package mbdo.dt.replanning.scada;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link ScadaClient} over the FactorySCADA REST API ({@code scada.base-url}, by default the local SCADA backend).
 */
@Component
public class RestScadaClient implements ScadaClient {

    private final RestClient restClient;

    public RestScadaClient(
            @Value("${scada.base-url:http://localhost:9000}") String baseUrl,
            @Value("${replanning.command-timeout-ms:60000}") long commandTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        // a command request waits for the machine to be idle, then for the command feedback
        requestFactory.setReadTimeout(Duration.ofMillis(commandTimeoutMs + 10_000));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl + "/api/replanning")
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public List<ScadaMachine> machines() {
        return call("read the SCADA machines", () -> restClient.get()
                .uri("/machines")
                .retrieve()
                .body(new ParameterizedTypeReference<List<ScadaMachine>>() { }));
    }

    @Override
    public ScadaMissionChange swapMission(String machine, String mission, String state, String expectedMission,
                                          String expectedState) {
        Map<String, Object> body = new HashMap<>();
        body.put("machine", machine);
        body.put("mission", mission);
        body.put("state", state);
        body.put("expectedMission", expectedMission);
        body.put("expectedState", expectedState);
        return post("/mission/swap", body, ScadaMissionChange.class,
                "swap " + machine + " to " + mission + (state == null ? "" : "::" + state));
    }

    @Override
    public ScadaMissionChange stopMission(String machine, String expectedMission, String expectedState) {
        Map<String, Object> body = new HashMap<>();
        body.put("machine", machine);
        body.put("expectedMission", expectedMission);
        body.put("expectedState", expectedState);
        return post("/mission/stop", body, ScadaMissionChange.class, "stop the mission of " + machine);
    }

    @Override
    public ScadaCommandResult executeCommand(String machine, String command, List<Map<String, Object>> parameters,
                                             long timeoutMs) {
        Map<String, Object> body = new HashMap<>();
        body.put("machine", machine);
        body.put("command", command);
        body.put("parameters", parameters);
        body.put("timeoutMs", timeoutMs);
        return post("/command", body, ScadaCommandResult.class, "send " + command + " to " + machine);
    }

    private <T> T post(String path, Map<String, Object> body, Class<T> responseType, String what) {
        return call(what, () -> restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(responseType));
    }

    private static <T> T call(String what, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            String message = "SCADA refused to " + what + ": " + e.getResponseBodyAsString();
            if (e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw new ScadaConflictException(message);
            }
            throw new ScadaException(message, e);
        } catch (RestClientException e) {
            throw new ScadaException("Could not " + what + ": " + e.getMessage(), e);
        }
    }
}
