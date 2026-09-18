package mbdo.dt.connectivity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class GatewayConfigurationLocator {

    private static final Logger logger = LoggerFactory.getLogger(GatewayConfigurationLocator.class);

    private final String configurationFilePath;

    public GatewayConfigurationLocator(ApplicationArguments args) {
        String argumentName = "configuration.path";

        if (args.containsOption(argumentName)) {
            configurationFilePath = "file:" + args.getOptionValues(argumentName).get(0);
            logger.info("Using external configuration: {}",
                    configurationFilePath);
        } else {
            configurationFilePath = "classpath:configuration.yml";
            logger.info("No configuration path argument provided. " +
                    "Using classpath:configuration.yml");
        }
    }

    public String getConfigurationFilePath() {
        return configurationFilePath;
    }
}