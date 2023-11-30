package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.ErrorProperty;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.beans.User;
import org.goobi.beans.Usergroup;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.enums.StepStatus;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.PropertyManager;
import de.sub.goobi.persistence.managers.StepManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@PluginImplementation
@Log4j2
public class DuplicateTasksStepPlugin implements IStepPluginVersion2 {
    
    @Getter
    private String title = "intranda_step_duplicate_tasks";
    @Getter
    private Step step;

    private String returnPath;

    private Process process;
    private int processId;
    private Prefs prefs;
    // name of the property holding value that shall be separated into smaller parts
    private String propertyName;
    // property value that shall be separated into smaller parts
    private String propertyValue;
    // separator that shall be used to separate the property value into smaller parts, by default \n 
    private String propertySeparator;
    // property parts after separation 
    private String[] props;
    // Step that shall be duplicated by this plugin
    private Step stepToDuplicate;
    // true if a step duplication is needed, false otherwise
    private boolean stepDuplicationEnabled;
    // three options for targetType for now: person | metadata | property. 
    // For person and metadata, the changes will be written into the METS file. 
    // For property the changes will be saved as process's property.
    private String targetType;
    // name of the new metadata's type or the new process property
    private String targetName;
    // true if an index should be used as suffices to the names of all the new metadata as well as process properties, false otherwise
    private boolean useIndex;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;

        process = step.getProzess();
        processId = process.getId();
        prefs = process.getRegelsatz().getPreferences();
                
        // read parameters from correct block in configuration file
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);
        log.info("DuplicateTasks step plugin initialized");

        // initialize property
        try {
            HierarchicalConfiguration propertyConfig = config.configurationAt("property");
            propertyName = propertyConfig.getString("@name", "");
            propertySeparator = propertyConfig.getString("@separator", "\n");

            String propertyTarget = propertyConfig.getString("@target", "");
            if (StringUtils.isBlank(propertyTarget) || !propertyTarget.contains(":")) {
                targetType = "property";
                targetName = propertyTarget;
            } else {
                String[] propertyTargetParts = propertyTarget.split(":");
                targetType = propertyTargetParts[0];
                targetName = propertyTargetParts[1];
            }

            useIndex = propertyConfig.getBoolean("@useIndex", true);

        } catch (IllegalArgumentException e) {
            // the <property> is missing
            String message = "The configuration for <property> is missing. Aborting...";
            logBoth(processId, LogType.ERROR, message);
            return;
        }

        if (StringUtils.isBlank(propertySeparator)) {
            propertySeparator = "\n";
        }

        // get the property value
        propertyValue = getPropertyValueFromProcess(process, propertyName);

        // split propertyValue into props
        props = propertyValue.split(propertySeparator);

        SubnodeConfiguration stepDuplicationConfig = config.configurationAt("stepToDuplicate");
        stepDuplicationEnabled = stepDuplicationConfig.getBoolean("@enabled", true);

        if (stepDuplicationEnabled) {
            String stepToDuplicateName = config.getString("stepToDuplicate", "");
            stepToDuplicate = getStepToDuplicate(process, stepToDuplicateName);
        }
    }

    /**
     * get the value of the property with the input name
     * 
     * @param process Goobi process
     * @param name name of the property
     * @return value of the property if it is found, otherwise an empty string
     */
    private String getPropertyValueFromProcess(Process process, String name) {
        String nameNoSpace = name.replace(" ", "_");
        List<Processproperty> properties = process.getEigenschaften();
        for (Processproperty property : properties) {
            String propName = property.getNormalizedTitle();
            if (propName.equals(nameNoSpace)) {
                return property.getWert();
            }
        }

        return "";
    }

    /**
     * get the step to duplicate
     * 
     * @param process Goobi process
     * @param stepName name of the step that shall be duplicated
     * @return the first step of the given name if the input name is not blank, otherwise the next step of the current one
     */
    private Step getStepToDuplicate(Process process, String stepName) {
        List<Step> steps = process.getSchritte();
        if (StringUtils.isBlank(stepName)) {
            // get the first step following the current one
            for (int i = 0; i < steps.size() - 1; ++i) {
                Step step = steps.get(i);
                if (this.step.equals(step)) {
                    return steps.get(i + 1);
                }
            }
        }

        // get the first step of name stepName
        for (Step step : steps) {
            String title = step.getTitel();
            if (stepName.equals(title)) {
                return step;
            }
        }

        String message = "Failed to find a proper step for duplication. Aborting...";
        logBoth(this.processId, LogType.ERROR, message);
        return null;
    }


    @Override
    public PluginGuiType getPluginGuiType() {
        // no GUI
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        // not used
        return "";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }
    
    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }
    
    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        // your logic goes here
        boolean successful = stepDuplicationEnabled ? processWithStepDuplication() : processWithoutStepDuplication();

        log.info("DuplicateTasks step plugin executed");

        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
    }

    /**
     * processing logic when step duplication is enabled
     * 
     * @return true if everything works out well, false otherwise
     */
    private boolean processWithStepDuplication() {
        return checkNecessaryFieldsForStepDuplication()
                && duplicateStepForEachEntry(stepToDuplicate, props)
                && deactivateStep(stepToDuplicate);
    }

    /**
     * processing logic when step duplication is disabled
     * 
     * @return true if everything works out well, false otherwise
     */
    private boolean processWithoutStepDuplication() {
        boolean result = true;
        for (int i = 0; i < props.length; ++i) {
            String targetNameToSave = StringUtils.isBlank(targetName) ? propertyName : targetName;
            if (useIndex) {
                targetNameToSave = getNewTitleWithOrder(targetNameToSave, i + 1);
            }
            result = result && addProcessPropertyOrMetadata(targetNameToSave, props[i], targetType);
        }

        return result;
    }

    /**
     * check if all necessary fields needed for further processing are available and valid
     * 
     * @return true if all necessary fields are available and valid, false otherwise
     */
    private boolean checkNecessaryFieldsForStepDuplication() {
        // 1. stepToDuplicate should not be null
        // 2. a blank propertyValue makes no sense
        return stepToDuplicate != null && StringUtils.isNotBlank(propertyValue);
    }

    /**
     * perform the duplication of the input step for each entry in the input props array
     * 
     * @param step the step that is to be duplicated
     * @param props array of strings, each of which will be recorded as value of a new process property
     * @return true if the duplication process is successful for all entries in the input array, false otherwise
     */
    private boolean duplicateStepForEachEntry(Step step, String[] props) {
        if (props == null) {
            // this is actually impossible, but only for the matter of completeness and double assurance
            return false;
        }

        boolean result = true;
        String origStepTitle = step.getTitel();

        for (int i = 0; i < props.length; ++i) {
            String newStepTitle = getNewTitleWithOrder(origStepTitle, i + 1);

            String targetNameToSave = targetName;
            if (StringUtils.isBlank(targetName)) {
                targetNameToSave = newStepTitle;
            } else if (useIndex) {
                targetNameToSave = getNewTitleWithOrder(targetName, i + 1);
            }

            result = result
                    && duplicateStep(step, newStepTitle)
                    && addProcessPropertyOrMetadata(targetNameToSave, props[i], targetType);
        }

        return result;
    }

    /**
     * get the new title based on the old title and an input order
     * 
     * @param title old title
     * @param order
     * @return new title
     */
    private String getNewTitleWithOrder(String title, int order) {
        return title + " (" + order + ")";
    }

    /**
     * duplicate the input step and name it with the input title
     * 
     * @param step the step that is to be duplicated
     * @param title title that shall be used to name the duplicated new step
     * @return true if the duplication is successful, false otherwise
     */
    private boolean duplicateStep(Step step, String title) {
        Step newStep = new Step();
        newStep.setProzess(this.process);
        newStep.setTitel(title);

        /* // =========================== fields that are NOT to be copied are: =========================== //
         * 1. id, title 
         *     - which must be different
         * 2. process, processId 
         *     - which is achieved by calling the setter on this.process directly
         * 3. bearbeitungsstatus, bearbeitungszeitpunkt, bearbeitungsbeginn, bearbeitungsende, bearbeitungsbenutzer, userId, messageQueue 
         *     - which do not have to be the same
         * // ============================================================================== // */

        newStep.setPrioritaet(step.getPrioritaet());
        newStep.setReihenfolge(step.getReihenfolge());

        newStep.setEditTypeEnum(step.getEditTypeEnum());

        newStep.setHomeverzeichnisNutzen(step.getHomeverzeichnisNutzen());
        newStep.setTypMetadaten(step.isTypMetadaten());
        newStep.setTypAutomatisch(step.isTypAutomatisch());
        newStep.setTypAutomaticThumbnail(step.isTypAutomaticThumbnail());
        newStep.setAutomaticThumbnailSettingsYaml(step.getAutomaticThumbnailSettingsYaml());
        newStep.setTypImportFileUpload(step.isTypImportFileUpload());
        newStep.setTypExportRus(step.isTypExportRus());
        newStep.setTypImagesLesen(step.isTypImagesLesen());
        newStep.setTypImagesSchreiben(step.isTypImagesSchreiben());
        newStep.setTypExportDMS(step.isTypExportDMS());
        newStep.setTypBeimAnnehmenModul(step.isTypBeimAnnehmenModul());
        newStep.setTypBeimAnnehmenAbschliessen(step.isTypBeimAnnehmenAbschliessen());
        newStep.setTypBeimAnnehmenModulUndAbschliessen(step.isTypBeimAnnehmenModulUndAbschliessen());
        newStep.setTypScriptStep(step.isTypScriptStep());
        newStep.setScriptname1(step.getScriptname1());
        newStep.setTypAutomatischScriptpfad(step.getTypAutomatischScriptpfad());
        newStep.setScriptname2(step.getScriptname2());
        newStep.setTypAutomatischScriptpfad2(step.getTypAutomatischScriptpfad2());
        newStep.setScriptname3(step.getScriptname3());
        newStep.setTypAutomatischScriptpfad3(step.getTypAutomatischScriptpfad3());
        newStep.setScriptname4(step.getScriptname4());
        newStep.setTypAutomatischScriptpfad4(step.getTypAutomatischScriptpfad4());
        newStep.setScriptname5(step.getScriptname5());
        newStep.setTypAutomatischScriptpfad5(step.getTypAutomatischScriptpfad5());
        newStep.setTypModulName(step.getTypModulName());
        newStep.setTypBeimAbschliessenVerifizieren(step.isTypBeimAbschliessenVerifizieren());
        newStep.setBatchStep(step.isBatchStep());
        newStep.setBatchSize(step.isBatchSize());
        newStep.setHttpStep(step.isHttpStep());
        newStep.setHttpUrl(step.getHttpUrl());
        newStep.setHttpMethod(step.getHttpMethod());

        String[] origPossibleHttpMethods = step.getPossibleHttpMethods();
        // make a copy of this array
        newStep.setPossibleHttpMethods(Arrays.stream(origPossibleHttpMethods).toArray(String[]::new));

        newStep.setHttpJsonBody(step.getHttpJsonBody());
        newStep.setHttpCloseStep(step.isHttpCloseStep());
        newStep.setHttpEscapeBodyJson(step.isHttpEscapeBodyJson());

        List<ErrorProperty> origPropertiesList = step.getEigenschaften();
        // make a copy of this list
        newStep.setEigenschaften(new ArrayList<>(origPropertiesList));

        List<User> origUsersList = step.getBenutzer();
        // make a copy of this list
        newStep.setBenutzer(new ArrayList<>(origUsersList));

        List<Usergroup> origUserGroupsList = step.getBenutzergruppen();
        // make a copy of this list
        newStep.setBenutzergruppen(new ArrayList<>(origUserGroupsList));

        newStep.setPanelAusgeklappt(step.isPanelAusgeklappt());
        newStep.setSelected(step.isSelected());
        newStep.setStepPlugin(step.getStepPlugin());
        newStep.setValidationPlugin(step.getValidationPlugin());
        newStep.setDelayStep(step.isDelayStep());
        newStep.setUpdateMetadataIndex(step.isUpdateMetadataIndex());
        newStep.setGenerateDocket(step.isGenerateDocket());

        try {
            StepManager.saveStep(newStep);
            return true;

        } catch (DAOException e) {
            String message = "Failed to save the duplicated new step: " + newStep.getTitel();
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;

        } catch (Exception e) {
            String message = "Unknown exception caught while trying to save the duplicated new step: " + newStep.getTitel();
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * switch method to control whether to call addMetadata or addProcessProperty
     * 
     * @param name name of the new Metadata or ProcessProperty that is to be created
     * @param value value of the new Metadata or ProcessProperty that is to be created
     * @param type three options for now: person | metadata | property. Can be extended further if needed.
     * @return true if the Metadata or ProcessProperty is successfully added, false otherwise
     */
    private boolean addProcessPropertyOrMetadata(String name, String value, String type) {
        switch (type.toLowerCase()) {
            case "person":
            case "metadata":
                return addMetadata(name, value, type);
            case "property":
                return addProcessProperty(name, value);
            default:
                // unknown type
                String message = "Unknown type '" + type + "'. Allowed types are metadata | person | property";
                logBoth(this.processId, LogType.ERROR, message);
                return false;
        }
    }

    /**
     * add a process property to the process
     * 
     * @param name property name
     * @param value property value
     * @return true if the process property is successfully created and added, false otherwise
     */
    private boolean addProcessProperty(String name, String value) {
        log.debug("adding process property '" + name + "' with value '" + value + "'");
        try {
            Processproperty property = new Processproperty();
            property.setTitel(name);
            property.setWert(value);
            property.setProzess(this.process);
            PropertyManager.saveProcessProperty(property);

            return true;

        } catch (Exception e) {
            String message = "Unknown exception caught while trying to add the process property: " + name;
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * add a metadata to the METS file
     * 
     * @param name name of the new metadata's type
     * @param value value of the new metadata
     * @param type two possibilities for now: person | metadata. Can be extended further if needed.
     * @return true if the metadata is successfully created and added, false otherwise
     */
    private boolean addMetadata(String name, String value, String type) {
        log.debug("adding metadata '" + name + "' with value '" + value + "'");
        try {
            Fileformat fileformat = process.readMetadataFile();
            DigitalDocument dd = fileformat.getDigitalDocument();
            DocStruct logical = dd.getLogicalDocStruct();
            MetadataType mdType = prefs.getMetadataTypeByName(name);
            boolean isPerson = "person".equalsIgnoreCase(type);
            Metadata md = createMetadata(mdType, value.trim(), isPerson);
            if (isPerson) {
                logical.addPerson((Person) md);
            } else {
                logical.addMetadata(md);
            }

            process.writeMetadataFile(fileformat);
            return true;

        } catch (ReadException | IOException | SwapException e) {
            // readMetadataFile
            String message = "Failed to read the METS file.";
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;

        } catch (PreferencesException e) {
            // getDigitalDocument
            String message = "Failed to load the digital document.";
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;

        } catch (MetadataTypeNotAllowedException e) {
            // createMetadata
            String message = "MetadataType '" + name + "' is not allowed.";
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;

        } catch (WriteException e) {
            // writeMetadataFile
            String message = "Failed to save the changes into METS file.";
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;

        } catch (Exception e) {
            String message = "Unknown exception caught while trying to add the metadata: " + name;
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * create Metadata
     * 
     * @param targetType MetadataType
     * @param value value of the new Metadata
     * @param isPerson
     * @return the new Metadata object created
     * @throws MetadataTypeNotAllowedException
     */
    private Metadata createMetadata(MetadataType targetType, String value, boolean isPerson) throws MetadataTypeNotAllowedException {
        // treat persons different than regular metadata
        if (isPerson) {
            Person p = new Person(targetType);
            int splitIndex = value.indexOf(" ");
            String firstName = value.substring(0, splitIndex);
            String lastName = value.substring(splitIndex);
            p.setFirstname(firstName);
            p.setLastname(lastName);

            return p;
        }

        Metadata md = new Metadata(targetType);
        md.setValue(value);

        return md;
    }

    /**
     * deactivate the input step
     * 
     * @param step step that shall be deactivated
     * @return true if the step is successfully deactivated, false otherwise
     */
    private boolean deactivateStep(Step step) {
        try {
            step.setBearbeitungsstatusEnum(StepStatus.DEACTIVATED);
            StepManager.saveStep(step);
            log.debug("The step with title '" + step.getTitel() + "' is deactivated.");
            return true;

        } catch (DAOException e) {
            String message = "Failed to deactivate the step: " + step.getTitel();
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;

        } catch (Exception e) {
            String message = "Unknown exception caught while trying to deactivate the step: " + step.getTitel();
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 
     * @param processId
     * @param logType
     * @param message message to be shown to both terminal and journal
     */
    private void logBoth(int processId, LogType logType, String message) {
        String logMessage = "Task Duplication Plugin: " + message;
        switch (logType) {
            case ERROR:
                log.error(logMessage);
                break;
            case DEBUG:
                log.debug(logMessage);
                break;
            case WARN:
                log.warn(logMessage);
                break;
            default: // INFO
                log.info(logMessage);
                break;
        }
        if (processId > 0) {
            Helper.addMessageToProcessJournal(processId, logType, logMessage);
        }
    }
}
