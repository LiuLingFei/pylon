package com.yotouch.core.entity;

import com.yotouch.core.Consts;
import com.yotouch.core.config.Configure;
import com.yotouch.core.entity.mf.MultiReferenceMetaFieldImpl;
import com.yotouch.core.exception.NoSuchMetaEntityException;
import com.yotouch.core.store.db.DbStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class EntityManagerImpl implements EntityManager {

    static final Logger logger = LoggerFactory.getLogger(EntityManagerImpl.class);

    @Autowired
    private DbStore dbStore;

    @Autowired
    private Configure config;

    @Value("${mysql.lowerCaseTableNames:}")
    private String lowerCaseTableNames;

    private Map<String, MetaEntityImpl> userEntities;
    private Map<String, MetaEntityImpl> mfEntities;

    private Map<String, MetaFieldImpl<?>> systemFields;
    private Map<String, List<String>>     systemValueOptions;

    public EntityManagerImpl() {
        this.userEntities = new HashMap<>();
        this.mfEntities = new HashMap<>();
        this.systemValueOptions = new HashMap<>();
    }

    private boolean isLowerCase() {
        return "true".equalsIgnoreCase(this.lowerCaseTableNames)
                || "1".equalsIgnoreCase(this.lowerCaseTableNames);

    }

    @PostConstruct
    private void initMetaEntities() {
        this.userEntities = new HashMap<>();
        this.mfEntities = new HashMap<>();

        loadSystemMetaFields();
        loadSystemMetaEntities();
        loadUserEntities();

        //loadFileMetaEntities("systemEntities.yaml", "");
        //loadFileMetaEntities("userEntities.yaml", "usr_");

        // Load new addon related entities
        //loadAppMetaEntities();

        loadDbMetaEntities();
        loadDbMetaFields();
        
        buildMultiReferenceEntities();

        rebuildDb();
    }

    private void buildMultiReferenceEntities() {
        scanMrEntities(this.userEntities.values());
    }

    private void scanMrEntities(Collection<MetaEntityImpl> entities) {
        for (MetaEntity me: entities) {
            for (MetaField<?> mf: me.getMetaFields()) {
                if (mf.isMultiReference()) {
                    buildMfMapping(me, mf);
                }
            }
        }
    }

    private void buildMfMapping(MetaEntity me, MetaField<?> mf) {
        MultiReferenceMetaFieldImpl mmf = (MultiReferenceMetaFieldImpl) mf;
        
        String targetEntityName = mf.getTargetMetaEntity().getName();
        logger.info("Catch MultiReference " + me.getName() + "=>" + mf.getName() + " target " + targetEntityName);
        
        String uuid = me.getName() + "_" + mf.getName() + "_" + targetEntityName;
        
        if (!StringUtils.isEmpty(me.getScope())) {
            uuid = me.getScope() + "_" + uuid;
        }
        
        MetaEntityImpl mei = new MetaEntityImpl(uuid, uuid, uuid,"mr_", me.getScope(), this.isLowerCase());
        mmf.setMappingMetaEntity(mei);
        
        Map<String, Object> fMap = new HashMap<>();
        fMap.put("dataType", Consts.META_FIELD_DATA_TYPE_UUID);
        fMap.put("name", "s_" + me.getName() + "Uuid");
        
        MetaFieldImpl<?> mfi = MetaFieldImpl.build(this, fMap);
        mei.addField(mfi);
        mfi.setMetaEntity(mei);
        
        
        fMap = new HashMap<>();
        fMap.put("dataType", Consts.META_FIELD_DATA_TYPE_UUID);
        fMap.put("name", "t_" + targetEntityName + "Uuid");
        
        mfi = MetaFieldImpl.build(this, fMap);
        mei.addField(mfi);
        mfi.setMetaEntity(mei);

        fMap = new HashMap<>();
        fMap.put("dataType", Consts.META_FIELD_DATA_TYPE_INT);
        fMap.put("name", "weight");

        mfi = MetaFieldImpl.build(this, fMap);
        mei.addField(mfi);
        mfi.setMetaEntity(mei);
        
        appendSysFields(uuid, mei);
        
        this.mfEntities.put(mei.getName(), mei);
    }

    private void addMetaEntity(MetaEntity me) {
        this.dbStore.createTable(me);
    }

    private void addMetaEntityFields(MetaEntity me) {
        this.dbStore.alterTable(me);
    }

    // CREATE OR ALTER TABLE
    public void rebuildDb() {


        List<String> tables = dbStore.fetchAllTables(this.isLowerCase());

        logger.info("Tables " + tables);

        scanExistingDbTable(tables, this.userEntities);
        scanExistingDbTable(tables, this.mfEntities);
    }

    private void scanExistingDbTable(List<String> tables, Map<String, MetaEntityImpl> entities) {
        for (String metaEntityTable: entities.keySet()) {
            MetaEntityImpl mei = (MetaEntityImpl) entities.get(metaEntityTable);
            if (tables.contains(mei.getTableName())) {
                addMetaEntityFields(mei);
            } else {
                addMetaEntity(mei);
            }
        }
    }

    private void loadUserEntities() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:/etc/**.entities.yaml");
            for (Resource resource : resources) {
                logger.info("Load user entity from classpath : " + resource.getFilename());
                String uri = resource.getURI().toString();
                if (uri.contains("pylon") && !uri.contains("sys.")) {
                    continue;
                }
                InputStream is = resource.getInputStream();
                loadMetaEntitiesFromInputStream(is, "usr_");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        File ytHome = config.getRuntimeHome();
        if (ytHome != null) {
            for (File ah : ytHome.listFiles()) {
                if (ah.isDirectory()) {
                    if (ah.getName().startsWith("addon-")
                            || ah.getName().startsWith("app-")) {
                        scanEtcUserEntities(ah);
                    }
                }
            }

            if (ytHome.getName().equals("pylon")) {
                scanEtcUserEntities(ytHome);
            }
        }
    }

    private void scanEtcUserEntities(File ah) {
        File etcHome = new File(ah, "etc");

        logger.info("Checking user entities in dir " + etcHome);

        if (etcHome.exists()) {
            for (File f: etcHome.listFiles()) {
                if (f.getName().equals("userEntities.yaml")
                        || f.getName().endsWith(".entities.yaml")) {
                    loadFileMetaEntities(f, "usr_");
                }
            }
        }
    }

    private void loadSystemMetaEntities() {
        // load file from classpath
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:/etc/systemEntities.yaml");
            for (Resource resource : resources) {
                logger.info("Load system entity " + resource.getURI());
                InputStream is = resource.getInputStream();
                loadMetaEntitiesFromInputStream(is, "");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        File ytHome = config.getRuntimeHome();
        if (ytHome != null) {
            File pylonHome = null;
            File appHome = null;
            for (File ah : ytHome.listFiles()) {
                if (ah.getName().equals("pylon")) {
                    pylonHome = ah;
                }

                if (ah.getName().startsWith("app-")) {
                    appHome = ah;
                }
            }

            logger.info("PYLON HOME " + pylonHome);
            logger.info("APP   HOME " + appHome);

            loadFileMetaEntities(new File(pylonHome, "etc/systemEntities.yaml"), "");
            if (appHome != null) {
                loadFileMetaEntities(new File(appHome, "etc/systemEntities.yaml"), "");
            }
        }
    }

    private void loadFileMetaEntitiesFormat2(Map<String, Object> m, String defaultPrefix) {
        @SuppressWarnings("unchecked")
        Map<String, Object> entities = (Map<String, Object>) m.get("entities");

        for (String en : entities.keySet()) {
            String uuid = "uuid-sys-" + en;

            Map<String, Object> emap = (Map<String, Object>) entities.get(en);
            String scope = null;
            if (emap.containsKey("scope")) {
                scope = (String) emap.get("scope");
            }

            String prefix = defaultPrefix;
            if (emap.containsKey("prefix")) {
                prefix = (String) emap.get("prefix");
            } else if (!StringUtils.isEmpty(scope)) {
                prefix = "";
            }
            
            String displayName = en;
            if (emap.containsKey("displayName")) {
                String dn = (String) emap.get("displayName");
                if (dn != null && !"".equals(dn)) {
                    displayName = dn;
                }
            }

            MetaEntityImpl mei = (MetaEntityImpl) this.userEntities.get(en);
            if (mei == null) {
                mei = new MetaEntityImpl(uuid, en, displayName, prefix, scope, this.isLowerCase());
            }


            Map<String, Object> fields = (Map<String, Object>) emap.get("fields");
            buildEntityFields(uuid, mei, fields);
            appendSysFields(uuid, mei);

            this.userEntities.put(mei.getName(), mei);
        }
    }
    
    private void loadMetaEntitiesFromInputStream(InputStream is, String prefix) {
        Yaml yaml = new Yaml();

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) yaml.load(is);
        if (m == null) {
            return;
        }

        if (m.containsKey("format")) {
            int format = (int) m.get("format");
            if (format == 2) {
                loadFileMetaEntitiesFormat2(m, prefix);
                return ;
            }
        }


        @SuppressWarnings("unchecked")
        Map<String, Object> entities = (Map<String, Object>) m.get("entities");

        for (String en : entities.keySet()) {
            String uuid = "uuid-sys-" + en;

            MetaEntityImpl mei = (MetaEntityImpl) this.userEntities.get(en);
            if (mei == null) {
                mei = new MetaEntityImpl(uuid, en, en, prefix, null, this.isLowerCase());
            }

            // parse files
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) entities.get(en);

            buildEntityFields(uuid, mei, fields);

            appendSysFields(uuid, mei);

            this.userEntities.put(mei.getName(), mei);
            logger.warn("Build System metaEntity " + mei);
            //logger.warn("Build System metaEntity fiels " + mei.getMetaFields());
        }
    }

    private void loadFileMetaEntities(File file, String prefix) {

        logger.info("Load entities " + file + " WITH defaultPrefix " + prefix);

        if (file.exists()) {
            
            try {
                InputStream is = new FileInputStream(file); 
                loadMetaEntitiesFromInputStream(is, prefix);

            } catch (FileNotFoundException e) {
                logger.error("Load system field error", e);
                return;
            }
        }

    }

    private void buildEntityFields(String uuid, MetaEntityImpl mei, Map<String, Object> fields) {
        for (String fn : fields.keySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fMap = (Map<String, Object>) fields.get(fn);

            fMap.put("name", fn);
            fMap.put("uuid", uuid + "-field-" + fn);

            MetaFieldImpl<?> mfi = MetaFieldImpl.build(this, fMap);
            mei.addField(mfi);
            mfi.setMetaEntity(mei);

            buildFieldValueOptions(mfi, fMap);
        }
    }

    private void buildFieldValueOptions(MetaField<?> mfi, Map<String, Object> fMap) {
        List<String> options = (ArrayList<String>) fMap.get("valueOption");
        if (options != null && !options.isEmpty()) {
            int weight = mfi.getValueOptions().size();
            for (String o : options) {
                mfi.addValueOption(ValueOption.build(mfi, o, weight++, false, false));
            }
        }
    }

    private void appendSysFields(String meUuid, MetaEntityImpl mei) {
        for (String sysFn : this.systemFields.keySet()) {
            boolean hasSysField = false;
            for (MetaField<?> mf : mei.getMetaFields()) {
                if (sysFn.equalsIgnoreCase(mf.getName())) {
                    hasSysField = true;
                    appendSysValueOptions(mf); //有这个metaField也append
                    break;
                }
            }

            if (hasSysField) {
                continue;
            }

            MetaFieldImpl<?> mfi = this.systemFields.get(sysFn);
            MetaFieldImpl<?> newF = mfi.copy(meUuid + "-field-" + mfi.getName());

            mei.addField(newF);
            newF.setMetaEntity(mei);
            appendSysValueOptions(newF); //没有这个字段也要append
        }
    }

    private void appendSysValueOptions(MetaField<?> mf) {
        if (this.systemValueOptions.containsKey(mf.getName())) { //这个mf有systemValueOptions
            int weight = mf.getValueOptions().size();
            for (String displayName : this.systemValueOptions.get(mf.getName())) {
                mf.addValueOption(ValueOption.build(mf, displayName, weight++, false, false));
            }
        }
    }

    private void loadSystemMetaFields() {

        this.systemFields = new HashMap<>();

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:/etc/systemFields.yaml");
            for (Resource resource : resources) {
                logger.info(resource.getDescription() + resource.getURI());
                InputStream is = resource.getInputStream();
                loadSysFieldsFromInputStream(is);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        File ytHome = config.getRuntimeHome();
        if (ytHome != null) {
            File pylonHome = null;
            File appHome = null;
            for (File ah : ytHome.listFiles()) {
                if (ah.getName().equals("pylon")) {
                    pylonHome = ah;
                }

                if (ah.getName().startsWith("app-")) {
                    appHome = ah;
                }
            }

            logger.info("PYLON HOME " + pylonHome);
            logger.info("APP   HOME " + appHome);


            loadSysFields(pylonHome);
            loadSysFields(appHome);
        }

    }

    private void loadSysFieldsFromInputStream(InputStream is) {
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) yaml.load(is);
        logger.info("Load system fields " + m);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) m.get("systemFields");

        for (String fn : fields.keySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fMap = (Map<String, Object>) fields.get(fn);
            fMap.put("name", fn);
            fMap.put("uuid", "-");
            fMap.put("type", "system");
            MetaFieldImpl<?> mfi = MetaFieldImpl.build(this, fMap);

            loadSysFieldValueOptions(mfi, fMap);

            this.systemFields.put(fn, mfi);
        }
    }

    private void loadSysFieldValueOptions(MetaField<?> metaField, Map<String, Object> fMap) {
        List<String> options = (ArrayList<String>) fMap.get("valueOption");
        if (options != null && !options.isEmpty()) {
            List<String> loadedOptions = new ArrayList<>();
            if (this.systemValueOptions.containsKey(metaField.getName())) {
                loadedOptions = this.systemValueOptions.get(metaField.getName());
            }
            for (String o : options) {
                if (!loadedOptions.contains(o)) {
                    loadedOptions.add(o);
                }
            }
            this.systemValueOptions.put(metaField.getName(), loadedOptions);
        }
    }

    private void loadSysFields(File appHome) {
        File ytEtcDir = new File(appHome, "etc");

        logger.warn("Load system meta fields " + ytEtcDir);

        File sysFieldFile = new File(ytEtcDir, "systemFields.yaml");

        logger.warn("Load system meta fields " + sysFieldFile + " exists " + sysFieldFile.exists());

        if (sysFieldFile.exists()) {
            
            try {
                InputStream is = new FileInputStream(sysFieldFile); 
                loadSysFieldsFromInputStream(is);
                //logger.warn("System fields " + this.systemFields);
            } catch (FileNotFoundException e) {
                logger.error("Load system field error", e);
                return;
            }
        }
    }

    private void loadDbMetaEntities() {
        logger.info("DbStore " + this.dbStore);
        MetaEntity me = this.getMetaEntity("metaEntity");
        try {
            List<Map<String, Object>> rows = dbStore.fetchAll(me);

            for (Map<String, Object> row : rows) {
                logger.info("Processing MetaEntity " + row.get("name") + " with UUID " + row.get("uuid"));

                MetaEntityImpl mei = this.buildMetaEntity(row);
                appendSysFields(mei.getUuid(), mei);
                this.userEntities.put(mei.getName(), mei);

                logger.info("Finish build user MetaEntity " + mei);
            }
        } catch (BadSqlGrammarException e) {
            logger.warn("No metaEntity table");
        }

    }
    
    private void loadDbMetaFields() {
        
        MetaEntity me = this.getMetaEntity("metaField");

        try {
            List<Map<String, Object>> rows = dbStore.fetchAll(me);
            for (Map<String, Object> row : rows) {
                logger.info("Processing MetaField " + row.get("name") + " with UUID " + row.get("uuid"));

                MetaFieldImpl<?> mfi = MetaFieldImpl.build(this, row);

                String meUuid = (String) row.get("metaEntityUuid");
                logger.info("Processing MetaField " + mfi + " for me " + meUuid);

                MetaEntityImpl mfMe = (MetaEntityImpl) this.getMetaEntity(meUuid);
                if (mfMe != null) {
                    MetaField<?> mfMeMetaField = mfMe.getMetaField(mfi.getName());
                    if (mfMeMetaField == null) {
                        mfMe.addField(mfi);
                        mfi.setMetaEntity(mfMe);
                        loadDbValueOptions(mfi);
                    } else {
                        loadDbValueOptions(mfMeMetaField);
                    }
                }
            }
        } catch (BadSqlGrammarException e) {
            logger.warn("No metaField table");
        }
    }

    private void loadDbValueOptions(MetaField<?> metaField) {
        try {
            MetaEntity valueOption = this.getMetaEntity("valueOption");
            List<Map<String, Object>> valueOptions = dbStore.fetchList(valueOption, "metaFieldUuid = ?", new Object[]{metaField.getUuid()});
            if (valueOptions != null && !valueOptions.isEmpty()) {
                for (Map<String, Object> row : valueOptions) {
                    if ("1".equals(row.get("deleted"))) {
                        continue;
                    }
                    logger.info("Processing ValueOption " + row.get("displayName") + " for mf " + metaField.getUuid());
                    Integer weight = row.get("weight") == null ? null : Integer.valueOf((String) row.get("weight"));
                    Integer checked = row.get("checked") == null ? 0 : Integer.valueOf((String) row.get("checked"));
                    metaField.addValueOption(ValueOption.build(metaField, (String) row.get("displayName"), weight, 1 == checked, false));
                }
            }
        } catch (BadSqlGrammarException e) {
            logger.warn("No valueOption table");
        }
    }

    private MetaEntityImpl buildMetaEntity(Map<String, Object> row) {
        
        String meName = (String) row.get("name");
        String meScope = (String) row.get("scope");
        String meUuid = (String) row.get("uuid");
        String displayName = (String) row.get("displayName");
        if (displayName == null && displayName.equals("")) {
            displayName = meName;
        }

        logger.info("Try to build MetaEntity " + meName + " with UUID " + meUuid);

        MetaEntity me = this.getMetaEntity("metaField");
        List<Map<String, Object>> fieldRows = dbStore.fetchList(me, "metaEntityUuid = ?",
                new Object[] { meUuid });

        logger.info("Get field data for " + meName + " fieldData " + fieldRows);

        MetaEntityImpl mei = new MetaEntityImpl(meUuid, meName, displayName,"usr_", meScope, this.isLowerCase());

        for (Map<String, Object> fr : fieldRows) {
            MetaFieldImpl<?> mfi = MetaFieldImpl.build(this, fr);
            mei.addField(mfi);
            mfi.setMetaEntity(mei);
        }

        return mei;

    }

    @Override
    public List<MetaEntity> getMetaEntities() {
        List<MetaEntity> l = new ArrayList<>(userEntities.values());
//        l.addAll(this.userEntities.values());
        return l;
    }

    @Override
    public MetaEntity getMetaEntity(String name) {
        MetaEntity me = this.userEntities.get(name);

        if (me == null) {
            me = this.mfEntities.get(name);
        }
        
        for (MetaEntity mmee: this.userEntities.values()) {
            if (mmee.getUuid().equals(name)) {
                return mmee;
            }
        }
        
        if (me == null) {
            throw new NoSuchMetaEntityException(name);
        }
        
        return me;
    }

    public void reload() {        
        initMetaEntities();
    }

}
