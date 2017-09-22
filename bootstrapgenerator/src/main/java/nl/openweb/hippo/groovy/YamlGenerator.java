/*
 * Copyright 2017 Open Web IT B.V. (https://www.openweb.nl/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.openweb.hippo.groovy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.plexus.util.FileUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import nl.openweb.hippo.groovy.annotations.Bootstrap;
import nl.openweb.hippo.groovy.annotations.Updater;
import nl.openweb.hippo.groovy.model.DefaultBootstrap;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toMap;
import static nl.openweb.hippo.groovy.Generator.NEWLINE;
import static nl.openweb.hippo.groovy.Generator.getInterpretingClass;
import static nl.openweb.hippo.groovy.Generator.getUpdater;
import static nl.openweb.hippo.groovy.Generator.stripAnnotations;
import static nl.openweb.hippo.groovy.model.Constants.Files.YAML_EXTENSION;
import static nl.openweb.hippo.groovy.model.Constants.NodeType.HIPPOSYS_UPDATERINFO;
import static nl.openweb.hippo.groovy.model.Constants.PropertyName.HIPPOSYS_BATCHSIZE;
import static nl.openweb.hippo.groovy.model.Constants.PropertyName.HIPPOSYS_DESCRIPTION;
import static nl.openweb.hippo.groovy.model.Constants.PropertyName.HIPPOSYS_DRYRUN;
import static nl.openweb.hippo.groovy.model.Constants.PropertyName.HIPPOSYS_PARAMETERS;
import static nl.openweb.hippo.groovy.model.Constants.PropertyName.HIPPOSYS_PATH;
import static nl.openweb.hippo.groovy.model.Constants.PropertyName.HIPPOSYS_QUERY;
import static nl.openweb.hippo.groovy.model.Constants.PropertyName.HIPPOSYS_SCRIPT;
import static nl.openweb.hippo.groovy.model.Constants.PropertyName.HIPPOSYS_THROTTLE;
import static nl.openweb.hippo.groovy.model.Constants.PropertyName.JCR_PRIMARY_TYPE;

/**
 * Generator to parse a groovy file to the bootstrap xmls
 */
public abstract class YamlGenerator {

    public static final String HCM_ACTIONS_NAME = "hcm-actions.yaml";

    protected YamlGenerator() {
    }

    /**
     * Parse file to updater node
     *
     * @param file the groovy file to use for source
     * @return Node object representing the groovy updater to marshall to xml
     */
    public static Map<String, Map<String, Object>> getUpdateYamlScript(final File file) {
        final String content;
        final Updater updater;
        try {
            content = FileUtils.fileRead(file);
            updater = getUpdater(file);
        } catch (final IOException e) {
            return null;
        }

        if (updater == null) {
            return null;
        }

        StringBuilder yaml = new StringBuilder();
        //location
        yaml.append(NEWLINE);
        Map<String, Object> properties = new LinkedHashMap<>();
        addNotEmptyProperty(JCR_PRIMARY_TYPE, HIPPOSYS_UPDATERINFO, properties);


        addNotEmptyProperty(HIPPOSYS_BATCHSIZE, updater.batchSize(), properties);
        addNotEmptyProperty(HIPPOSYS_DESCRIPTION, updater.description(), properties);
        addNotEmptyProperty(HIPPOSYS_DRYRUN, updater.dryRun(), properties);
        addNotEmptyProperty(HIPPOSYS_PARAMETERS, updater.parameters(), properties);
        addNotEmptyProperty(HIPPOSYS_PATH, updater.path(), properties);
        addNotEmptyProperty(HIPPOSYS_QUERY, updater.xpath(), properties);
        addNotEmptyProperty(HIPPOSYS_SCRIPT, processScriptContent(content), properties);
        addNotEmptyProperty(HIPPOSYS_THROTTLE, updater.throttle(), properties);
        return Collections.singletonMap(getBootstrapPath(file), properties);
    }

    /**
     * Do some useful tweaks to make the script pleasant and readable
     */
    private static String processScriptContent(final String script) {
        final String stripAnnotations = stripAnnotations(script, Bootstrap.class, Updater.class);
        return removeEmptyIndents(stripAnnotations);
    }

    private static String removeEmptyIndents(String content){
        return content.replaceAll(NEWLINE + "\\s+" + NEWLINE, NEWLINE + NEWLINE);
    }

    public static String getSingleQuoteWrapped(final String value){
        return StringUtils.isNotBlank(value)
                ? "'" + value + "'" + NEWLINE
                : StringUtils.EMPTY;
    }

    private static void addNotEmptyProperty(final String name, final Object value, final Map<String, Object> properties) {
        if(value != null && (!(value instanceof String) || StringUtils.isNotBlank((String)value))){
            properties.put(name, value);
        }
    }

    /**
     * Get update script yaml filename
     *
     * @param basePath path to make returning path relative to
     * @param file     File object for the groovy script
     * @return the path, relative to the basePath, converted \ to /, with yaml extension
     */
    public static String getUpdateScriptYamlFilename(final File basePath, final File file) {
        final String fileName = file.getAbsolutePath().substring(basePath.getAbsolutePath().length() + 1);
        return FilenameUtils.removeExtension(FilenameUtils.separatorsToUnix(fileName)) + YAML_EXTENSION;
    }

    /**
     * Generate hcm-action.yaml to reload updaters
     *
     * @param sourcePath        sourcepath of groovy files
     * @param targetDir         the target where the ecmExtensions from resources would be
     * @param files             groovy files, need to be relative to the source path
     */
    public static String getHcmActionsList(final File sourcePath, final File targetDir, final List<File> files) throws IOException {
        Map<String, String> collect = files.stream()
                .map(YamlGenerator::getBootstrapPath)
                .filter(Objects::nonNull)
                .collect(toMap(key -> key, key -> "reload"));

        final List<Map<Double, Object>> hcmHcmActionsSource = getExistingHcmActionsSequence(sourcePath);

        if(hcmHcmActionsSource.isEmpty() && collect.isEmpty()){
            return null; // target will remain as is
        }

        final List<Map<Double, Object>> hcmActionsTarget = getExistingHcmActionsSequence(targetDir);

        Map<Double, Object> collected = collect.isEmpty() ?
                Collections.emptyMap() :
                singletonMap(0.1, collect);

        return getHcmActionsYaml(Collections.singletonList(collected), hcmActionsTarget, hcmHcmActionsSource);
    }

    private static List<Map<Double, Object>> getExistingHcmActionsSequence(final File sourcePath) throws FileNotFoundException {
        final File extensions = new File(sourcePath, HCM_ACTIONS_NAME);
        if(extensions.exists()){
            Yaml yaml = new Yaml();
            Map<String, List<Map<Double, Object>>> load = (Map<String, List<Map<Double, Object>>>) yaml.load(new FileInputStream(extensions));
            return load.get("action-lists");
        }
        return Collections.emptyList();
    }

    @SafeVarargs
    private static String getHcmActionsYaml(final List<Map<Double, Object>> ... sequences){
        //collect all maps on a key
        Map<Double, Map<String, String>> collectMap = new TreeMap<>();
        for (List<Map<Double, Object>> list : sequences) {
            for (Map<Double, Object> map : list) {
                //seems all maps have just one entry
                Iterator<Map.Entry<Double, Object>> iterator = map.entrySet().iterator();
                while(iterator.hasNext()) {
                    Map.Entry<Double, Object> entry = iterator.next();
                    Double key = entry.getKey();
                    if (collectMap.containsKey(key)) {
                        Map<String, String> objects = collectMap.get(key);
                        objects.putAll((Map<String, String>) map.get(key));
                    }  else {
                        collectMap.put(key, (Map<String, String>)map.get(key));
                    }
                }
            }
        }

        List<Map<Double, Object>> collect = new ArrayList<>();
        for (Map.Entry<Double, Map<String, String>> entry : collectMap.entrySet()) {
            collect.add(Collections.singletonMap(entry.getKey(), entry.getValue()));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("action-lists", collect);

        return getYamlString(out);
    }

    private static String getBootstrapPath(final File file){
        try {
            final Class scriptClass = getInterpretingClass(file);
            Bootstrap bootstrap = (Bootstrap) (scriptClass.isAnnotationPresent(Bootstrap.class) ?
                    scriptClass : DefaultBootstrap.class).getAnnotation(Bootstrap.class);
            if (bootstrap.reload()) {
                Updater updater = (Updater) scriptClass.getDeclaredAnnotation(Updater.class);
                String contentroot = "queue";
                if (bootstrap.contentroot().equals("registry")) {
                    contentroot = bootstrap.contentroot();
                }
                return "/hippo:configuration/hippo:update/hippo:" + contentroot + "/" + updater.name();
            }
        } catch (IOException e) {
        }
        return null;
    }

    public static String getYamlString(Map<?,?> map){
        return map == null ? StringUtils.EMPTY : getYaml().dump(map);
    }

    public static void writeToYamlFile(Map<?,?> map, File file) throws IOException {
        if(map != null){
            getYaml().dump(map, new FileWriter(file, false));
        }
    }

    private static Yaml getYaml(){
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options);
    }
}