package health.emrys.afon.infra;

import org.slf4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser {
    static final Logger logger = org.slf4j.LoggerFactory.getLogger(LogParser.class);

    public String searchForTextAfter(String searchTerm, String logs) {
        logger.info("Logs are {} characters long", logs.length());
        Pattern pattern = Pattern.compile(searchTerm + "(.*)");
        Matcher matcher = pattern.matcher(logs);
        matcher.find();
        if (matcher.groupCount() < 2) {
            logger.error("Cannot find text '{}' in\n{}", searchTerm, logs);
        }
        return matcher.group(1);
    }
}
