/*
 * Copyright 2019 Open Web IT B.V. (https://www.openweb.nl/)
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
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jcr.NamespaceException;

import org.apache.jackrabbit.spi.NameFactory;
import org.apache.jackrabbit.spi.commons.conversion.NameParser;
import org.apache.jackrabbit.spi.commons.name.NameFactoryImpl;
import org.apache.jackrabbit.spi.commons.namespace.NamespaceMapping;
import org.codehaus.plexus.util.FileUtils;

import groovy.lang.GroovyClassLoader;
import nl.openweb.hippo.groovy.exception.ScriptParseException;
import nl.openweb.hippo.groovy.model.ScriptClass;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static nl.openweb.hippo.groovy.Generator.NEWLINE;
import static nl.openweb.hippo.groovy.Generator.getAnnotationClasses;
import static nl.openweb.hippo.groovy.Generator.stripAnnotations;

public class ScriptClassFactory {
    private static final String LINE_END_WINDOWS = "\r\n";
    private static final String LINE_END_LINUX = "\n";
    private static final String LINE_END_MAC = "\r";
    private static final NamespaceMapping namespaceResolver = new NamespaceMapping();
    private static final NameFactory nameFactory = NameFactoryImpl.getInstance();
    private static GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
    private static final String ANNOTATIONS_REGEX = getAnnotationClasses().stream()
                .map(annotation -> annotation.getCanonicalName().replace(".", "\\.") + "|" + annotation.getSimpleName())
            .collect(joining("|"));

    private ScriptClassFactory() {
        //No instantiating of this class
    }

    private static NamespaceMapping getNamespaceResolver() throws NamespaceException {
        if(!namespaceResolver.hasPrefix("")){
            namespaceResolver.setMapping("", "");
        }
        return namespaceResolver;
    }

    private static void validateScriptClass(final ScriptClass scriptClass) {
        if (scriptClass == null || scriptClass.getUpdater() == null) {
            return;
        }
        final String name = scriptClass.getUpdater().name();

        try {
            NameParser.parse(name, getNamespaceResolver(), nameFactory);
        } catch (Exception e) {
            throw new ScriptParseException("Error parsing the updater name for: " + scriptClass.getFile().getAbsolutePath(), e);
        }
    }

    /**
     * Returns a class that has actually nothing but the Bootstrap and Updater Annotations
     *
     * @param file the file to make a class representation of
     * @return a fake class with the Bootstrap and Updater annotations
     */
    public static ScriptClass getInterpretingClass(final File file) {
        return getInterpretingClass(file, false);
    }

    /**
     * Returns a class that has actually nothing but the Bootstrap and Updater Annotations
     *
     * @param file the file to make a class representation of
     * @param keepLineCount keep linecount when stripping the annotations in the scriptcontent
     * @return a fake class with the Bootstrap and Updater annotations
     */
    public static ScriptClass getInterpretingClass(final File file, final boolean keepLineCount) {
        groovyClassLoader.clearCache();
        String script;
        try {
            script = readFileEnsuringLinuxLineEnding(file);

            String imports = getAnnotationClasses().stream()
                    .map(clazz -> "import " + clazz.getCanonicalName() + ";")
                    .collect(joining());

            String annotations = getAnnotations(script);
            script = stripAnnotations(script, keepLineCount);

            String interpretCode = imports + annotations + script.replaceAll("import .+\n", "")
                    .replaceAll("package\\s.*\n", "")
                    .replaceAll("extends\\s.*\\{[^\\u001a]*", "{}");

            interpretCode = scrubAnnotations(interpretCode);
            final ScriptClass scriptClass = new ScriptClass(file, groovyClassLoader.parseClass(interpretCode), script);
            validateScriptClass(scriptClass);
            return scriptClass;
        } catch (IOException e) {
            return null;
        }
    }

    public static String readFileEnsuringLinuxLineEnding(final File file) throws IOException {
        String content = FileUtils.fileRead(file);
        if (content.contains(LINE_END_MAC)) {
            content = content.replaceAll(LINE_END_WINDOWS, LINE_END_LINUX)
                    .replaceAll(LINE_END_MAC, LINE_END_LINUX);
        }
        return content.replaceAll("[\\t ]+" + NEWLINE, NEWLINE);
    }

    private static String scrubAnnotations(final String interpretCode) {
        return interpretCode.replaceAll("@((?!" + ANNOTATIONS_REGEX + ")[\\w]+)([\\s]+|(\\([^\\)]*\\)))", "");
    }

    private static String getAnnotations(final String sourceCode){
        String result = "\n";
        final List<Class<?>> annotationClasses = getAnnotationClasses();
        for (Class<?> annotationClass : annotationClasses) {
            final String regex1 = Generator.getAnnotationRegex(annotationClass.getSimpleName());
            final String regex2 = Generator.getAnnotationRegex(annotationClass.getCanonicalName());
            final Matcher matcher1 = Pattern.compile(regex1).matcher(sourceCode);
            final Matcher matcher2 = Pattern.compile(regex2).matcher(sourceCode);
            if(matcher1.find()){
                result += matcher1.group();
            }
            if(matcher2.find()){
                result += matcher2.group();
            }
        }
        return result;
    }

    public static List<ScriptClass> getScriptClasses(File sourceDir) {
        return Generator.getGroovyFiles(sourceDir).stream().map(ScriptClassFactory::getInterpretingClass)
                .filter(script -> script.isValid() && !script.isExcluded()).collect(toList());
    }
}
