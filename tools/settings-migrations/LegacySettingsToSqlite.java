/*
 * One-off external migration tool. This source is intentionally not part of the SDRTrunk runtime build.
 */
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import io.github.dsheirer.preference.spectrum.SpectrumPreference;
import io.github.dsheirer.settings.ColorSetting;
import io.github.dsheirer.settings.Settings;
import io.github.dsheirer.source.tuner.configuration.TunerSettings;
import java.awt.Color;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.prefs.Preferences;

public class LegacySettingsToSqlite
{
    private static final String LEGACY_UI_KEY = "default";
    private static final String LEGACY_SETTINGS_MARKER = "settings_config_initialized";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static void main(String[] args) throws Exception
    {
        if(args.length < 1 || args.length > 3)
        {
            System.err.println("Usage: LegacySettingsToSqlite <sdrtrunk.sqlite> [SDRTrunk.properties] " +
                "[tuner_configuration.json]");
            System.exit(2);
        }

        Path database = Path.of(args[0]).toAbsolutePath().normalize();
        Path propertiesPath = args.length >= 2 ? Path.of(args[1]).toAbsolutePath().normalize() : null;
        Path tunerPath = args.length >= 3 ? Path.of(args[2]).toAbsolutePath().normalize() : null;

        if(!Files.isRegularFile(database))
        {
            throw new IllegalArgumentException("Database does not exist: " + database);
        }

        TunerSettings tunerSettings = tunerPath != null && Files.isRegularFile(tunerPath) ?
            MAPPER.readValue(tunerPath.toFile(), TunerSettings.class) : null;
        checkpoint(database);
        Path backup = database.resolveSibling(database.getFileName() + ".pre-settings-migration-" +
            TIMESTAMP.format(LocalDateTime.now()) + ".bak");
        Files.copy(database, backup, StandardCopyOption.COPY_ATTRIBUTES);

        ApplicationSettingsStore store = new ApplicationSettingsStore(database);
        Settings ui = store.load(ApplicationSettingsStore.UI_SETTINGS, Settings.class)
            .or(() -> uncheckedLoad(store, LEGACY_UI_KEY, Settings.class)).orElseGet(Settings::new);
        Properties properties = loadProperties(propertiesPath);
        applyLegacyColors(ui, properties);
        store.save(ApplicationSettingsStore.UI_SETTINGS, ui);

        if(tunerSettings != null)
        {
            store.save(ApplicationSettingsStore.TUNER_SETTINGS, tunerSettings);
        }

        migratePreferences(database, properties);
        removeLegacySettingsKeys(database);

        Settings verifiedUi = store.load(ApplicationSettingsStore.UI_SETTINGS, Settings.class).orElseThrow();
        int tunerCount = store.load(ApplicationSettingsStore.TUNER_SETTINGS, TunerSettings.class)
            .map(settings -> settings.getTunerConfigurations().size()).orElse(0);
        System.out.println("Backup: " + backup);
        System.out.println("UI settings: " + verifiedUi.getSettings().size());
        System.out.println("Tuner configurations: " + tunerCount);
        System.out.println("Legacy source files were left unchanged.");
    }

    private static <T> java.util.Optional<T> uncheckedLoad(ApplicationSettingsStore store, String key, Class<T> type)
    {
        try
        {
            return store.load(key, type);
        }
        catch(Exception e)
        {
            throw new IllegalStateException("Unable to load legacy settings key: " + key, e);
        }
    }

    private static Properties loadProperties(Path path) throws Exception
    {
        Properties properties = new Properties();

        if(path != null && Files.isRegularFile(path))
        {
            try(InputStream input = Files.newInputStream(path))
            {
                properties.load(input);
            }
        }

        return properties;
    }

    private static void applyLegacyColors(Settings settings, Properties properties)
    {
        applyColor(settings, properties, "audio.channel.panel.color.background",
            ColorSetting.ColorSettingName.AUDIO_CHANNEL_BACKGROUND);
        applyColor(settings, properties, "audio.channel.panel.color.label",
            ColorSetting.ColorSettingName.AUDIO_CHANNEL_LABEL);
        applyColor(settings, properties, "audio.channel.panel.color.muted",
            ColorSetting.ColorSettingName.AUDIO_CHANNEL_MUTED);
        applyColor(settings, properties, "audio.channel.panel.color.value",
            ColorSetting.ColorSettingName.AUDIO_CHANNEL_VALUE);
    }

    private static void applyColor(Settings settings, Properties properties, String key,
                                   ColorSetting.ColorSettingName name)
    {
        String value = properties.getProperty(key);

        if(value != null)
        {
            ColorSetting setting = settings.getColorSetting(name);

            if(setting == null)
            {
                setting = new ColorSetting(name);
                settings.addSetting(setting);
            }

            setting.setColor(new Color(Integer.parseInt(value), true));
        }
    }

    private static void migratePreferences(Path database, Properties properties) throws Exception
    {
        SqlitePreferencesFactory.install(database);

        try
        {
            SpectrumPreference spectrum = new SpectrumPreference(ignored -> { });
            String enabled = properties.getProperty(SpectrumPreference.KEY_DISPLAY_ENABLED);
            String dftSize = properties.getProperty(SpectrumPreference.KEY_DFT_SIZE);
            String frameRate = properties.getProperty(SpectrumPreference.KEY_FRAME_RATE);
            String browse = properties.getProperty("AddRecordingTunerDialog.lastBrowseLocation");

            if(enabled != null)
            {
                spectrum.setDisplayEnabled(Boolean.parseBoolean(enabled));
            }

            if(dftSize != null)
            {
                spectrum.setDftSize(io.github.dsheirer.spectrum.DFTSize.valueOf(dftSize));
            }

            if(frameRate != null)
            {
                spectrum.setFrameRate(Integer.parseInt(frameRate));
            }

            if(browse != null && !browse.isBlank())
            {
                new DirectoryPreference(ignored -> { }).setLastRecordingBrowseDirectory(Path.of(browse));
            }

            Preferences.userRoot().flush();
        }
        finally
        {
            SqlitePreferencesFactory.shutdown();
        }
    }

    private static void removeLegacySettingsKeys(Path database) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM application_settings WHERE key = ?"))
            {
                statement.setString(1, LEGACY_UI_KEY);
                statement.executeUpdate();
            }

            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM database_metadata WHERE key = ?"))
            {
                statement.setString(1, LEGACY_SETTINGS_MARKER);
                statement.executeUpdate();
            }
        }
    }

    private static void checkpoint(Path database) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }
}
