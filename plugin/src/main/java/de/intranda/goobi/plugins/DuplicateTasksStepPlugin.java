package de.intranda.goobi.plugins;

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
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.PropertyManager;
import de.sub.goobi.persistence.managers.StepManager;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class DuplicateTasksStepPlugin implements IStepPluginVersion2 {
    
    @Getter
    private String title = "intranda_step_duplicate_tasks";
    @Getter
    private Step step;
    @Getter
    private String value;
    @Getter 
    private boolean allowTaskFinishButtons;
    private String returnPath;

    private Process process;
    private int processId;

    private String propertyName;

    private String propertyValue;

    private String propertySeparator;

    private String[] props;

    private Step stepToDuplicate;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;

        process = step.getProzess();
        processId = process.getId();
                
        // read parameters from correct block in configuration file
        SubnodeConfiguration config = ConfigPlugins.getProjectAndStepConfig(title, step);
        value = config.getString("value", "default value");
        allowTaskFinishButtons = config.getBoolean("allowTaskFinishButtons", false);
        log.info("DuplicateTasks step plugin initialized");
        log.debug("value = " + value);

        // initialize property
        HierarchicalConfiguration propertyConfig = config.configurationAt("property");
        propertyName = propertyConfig.getString("@name", "");
        propertySeparator = propertyConfig.getString("@separator", "\n");
        if (StringUtils.isBlank(propertySeparator)) {
            propertySeparator = "\n";
        }
        log.debug("propertyName = " + propertyName);
        log.debug("separator = " + propertySeparator);

        // get the property value
        propertyValue = getPropertyValueFromProcess(process, propertyName);
        log.debug("propertyValue = " + propertyValue);

        // split propertyValue into props
        props = propertyValue.split(propertySeparator);
        log.debug("props has " + props.length + " elements:");
        for (String prop : props) {
            log.debug("prop = " + prop);
        }

        String stepToDuplicateName = config.getString("stepToDuplicate", "");
        stepToDuplicate = getStepToDuplicate(process, stepToDuplicateName);
        log.debug("stepToDuplicate has title = " + stepToDuplicate.getTitel());
    }

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

    private Step getStepToDuplicate(Process process, String stepName) {
        log.debug("stepName = " + stepName);

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

        return null;
    }


    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.FULL;
        // return PluginGuiType.PART;
        // return PluginGuiType.PART_AND_FULL;
        // return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_duplicate_tasks.xhtml";
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
        boolean successful = checkNecessaryFields();
        // your logic goes here
        
        successful = successful && duplicateStepForEachEntry(stepToDuplicate, props);

        log.info("DuplicateTasks step plugin executed");

        return successful ? PluginReturnValue.FINISH : PluginReturnValue.ERROR;
    }

    private boolean checkNecessaryFields() {
        // 1. stepToDuplicate should not be null
        // 2. a blank propertyValue makes no sense
        return stepToDuplicate != null && StringUtils.isNotBlank(propertyValue);
    }

    private boolean duplicateStepForEachEntry(Step step, String[] props) {
        if (props == null) {
            // this is actually impossible, but only for the matter of completeness and double assurance
            return false;
        }

        boolean result = true;
        String origTitle = step.getTitel();

        for (int i = 0; i < props.length; ++i) {
            String newTitle = getNewTitleWithOrder(origTitle, i + 1);
            // duplicate the step
            result = result && duplicateStep(step, newTitle);
            // add process property 
            result = result && addPropertyOfDuplication(newTitle, props[i]);
        }

        return result;
    }

    private String getNewTitleWithOrder(String title, int order) {
        return title + " (" + order + ")";
    }

    private boolean duplicateStep(Step step, String title) {
        Step newStep = new Step();
        newStep.setProzess(this.process);

        newStep.setTitel(title);
        newStep.setPrioritaet(step.getPrioritaet());
        newStep.setReihenfolge(step.getReihenfolge());
        //        newStep.setBearbeitungsstatusAsString(step.getBearbeitungsstatusAsString());
        //        newStep.setBearbeitungszeitpunkt(step.getBearbeitungszeitpunkt());
        //        newStep.setBearbeitungsbeginn(step.getBearbeitungsbeginn());
        //        newStep.setBearbeitungsende(step.getBearbeitungsende());
        newStep.setEditTypeEnum(step.getEditTypeEnum());
        //        newStep.setBearbeitungsbenutzer(step.getBearbeitungsbenutzer());
        //        newStep.setUserId(step.getUserId());
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

        //        boolean userGroupsEquals = newStep.getBenutzergruppen() == step.getBenutzergruppen();
        //        log.debug("userGroupsEquals = " + userGroupsEquals);

        newStep.setPanelAusgeklappt(step.isPanelAusgeklappt());
        newStep.setSelected(step.isSelected());
        newStep.setStepPlugin(step.getStepPlugin());
        newStep.setValidationPlugin(step.getValidationPlugin());
        newStep.setDelayStep(step.isDelayStep());
        newStep.setUpdateMetadataIndex(step.isUpdateMetadataIndex());
        newStep.setGenerateDocket(step.isGenerateDocket());

        // not copied fields are: id, process, processId, messageQueue

        try {
            StepManager.saveStep(newStep);

        } catch (DAOException e) {
            String message = "Failed to save the duplicated new step: " + newStep.getTitel();
            logBoth(this.processId, LogType.ERROR, message);
            e.printStackTrace();
            return false;
        }

        return true;
    }

    private boolean addPropertyOfDuplication(String name, String value) {
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
