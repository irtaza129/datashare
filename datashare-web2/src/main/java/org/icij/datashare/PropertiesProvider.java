package org.icij.datashare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

public class PropertiesProvider {
    private Logger logger = LoggerFactory.getLogger(getClass());
    static String DEFAULT_NAME="datashare.properties";
    private final String fileName;
    private Properties cachedProperties;

    public PropertiesProvider() {
        fileName = DEFAULT_NAME;
    }

    public PropertiesProvider(String fileName) {
        this.fileName = fileName;
    }

    public Properties getProperties() {
        if (cachedProperties == null) {
            cachedProperties = new Properties();
            InputStream inStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);
            try {
                cachedProperties.load(inStream);
            } catch (IOException|NullPointerException e) {
                logger.warn("no datashare.properties found, using empty properties");
            }
        }
        return cachedProperties;
    }

    public Optional<String> getIfPresent(final String propertyName) {
        return getProperties().getProperty(propertyName) == null ?
                Optional.empty():
                Optional.of((getProperties().getProperty(propertyName)));
    }
}
