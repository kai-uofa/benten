package com.intuit.benten;

import com.intuit.benten.jenkins.exceptions.BentenJenkinsException;
import com.intuit.benten.jenkins.properties.JenkinsProperties;
import com.intuit.benten.jenkins.model.JenkinsJobBuildParameter;
import com.jayway.jsonpath.JsonPath;
import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.client.JenkinsHttpClient;
import com.offbytwo.jenkins.model.Build;
import com.offbytwo.jenkins.model.BuildResult;
import com.offbytwo.jenkins.model.Job;
import com.offbytwo.jenkins.model.JobWithDetails;
import com.offbytwo.jenkins.model.QueueReference;
import net.minidev.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by sshashidhar on 2/22/18.
 */
@Component
public class BentenJenkinsClient {

    @Autowired
    private JenkinsProperties jenkinsProperties;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private JenkinsServer jenkins;
    private JenkinsHttpClient jenkinsHttpClient;


    @PostConstruct
    public void init(){
        try{

            jenkins = new JenkinsServer(new URI(jenkinsProperties.getBaseurl()), jenkinsProperties.getUsername(), jenkinsProperties.getPassword());

            jenkinsHttpClient = new JenkinsHttpClient(new URI(jenkinsProperties.getBaseurl()),jenkinsProperties.getUsername(),jenkinsProperties.getPassword());

           // logger.info("Connection to Jenkins Server returned: " + jenkins.isRunning());
           // logger.info("Version returned from Jenkins http Client: " + jenkinsHttpClient.getJenkinsVersion());
        }catch (Exception e){
            logger.error("Failed to Connect to Jenkins Instance");
            e.printStackTrace();
        }

    }

    public JobWithDetails getJobByJobName(String jobName){
        try{
            logger.info("Get Jenkins job by JobName: " + jobName);
            return jenkins.getJob(jobName);
        }catch (Exception e){
            logger.error("Failed to get Jenkins job by JobName: " + jobName);
            e.printStackTrace();
            throw new BentenJenkinsException(e.getMessage());
        }
    }

    public Map<String, String> getAllJobsWithPrefix(String prefix){
        try {
            logger.info("Searching Jenkins jobs with Prefix: " + prefix);
            Map<String, String> filteredJobs = jenkins.getJobs().entrySet().stream()
                    .filter(map -> map.getValue().getName().startsWith(prefix))
                    .collect(Collectors.toMap(p -> p.getKey(), p -> p.getValue().getName()));

            logger.debug("Filtered Jobs key: " + filteredJobs.keySet());
            logger.debug("Filtered Jobs Value: " + filteredJobs.values().toString());

            return filteredJobs;

        } catch (IOException e) {
            logger.error("Failed to search Jenkins jobs with Prefix: " + prefix);
            e.printStackTrace();
            throw new BentenJenkinsException(e.getMessage());
        }
    }

    public String build(String jobName, String jobParams){
        logger.info("Building Jenkins job with jobName: " + jobName + " with params: " + jobParams);

        try {
            Job job = jenkins.getJob(jobName);
            String logInfo = jobName + " is buildable: " + job.details().isBuildable();
            logger.info(logInfo);

            int nextBuildNumber = job.details().getNextBuildNumber();

            List<JenkinsJobBuildParameter> buildParams = getBuildParams(jobName);
            
            QueueReference queueReference = null;
            if (buildParams.size() < 1) {
                if (jobParams != ""){
                    logInfo = jobName + " does not need parameters but " + jobParams + " has been passed in.";
                    logger.info(logInfo);
                }
                queueReference = job.build(true);
            }
            else {
                if (jobParams == ""){
                    logInfo = jobName + " needs " + buildParams.size() + "parameters but nothing has been passed in. Commence build with default job params.";
                    logger.info(logInfo);
                }
                
                Map<String, String> params = constructJobParams(buildParams, jobParams);
                queueReference = job.build(params, true);
            }

            logger.debug(job.toString());
            int waitFor = 0;
            while(job.details().isInQueue()){
                waitFor++;
                logger.info("Job in queue");
                Thread.sleep(5000);
                if(waitFor>4) return logInfo + " Job is built successfully, but is in Queue";
            }

            JobWithDetails jobWithDetails =job.details();
            if(jobWithDetails.getBuildByNumber(nextBuildNumber).details().isBuilding()) {
                logger.info("Jenkins job "+ jobName +" is building with Build Number: " + nextBuildNumber);
                return logInfo + " Jenkins job "+ jobName +" is building with Build Number: " + nextBuildNumber;
            }

            // build is not building, must be something else
            BuildResult buildResult = jobWithDetails.getBuildByNumber(nextBuildNumber).details().getResult();
            String buildInfo = "Jenkins job " + jobName + " with Build Number " + nextBuildNumber + " has been " + buildResult.toString();
            logger.info(buildInfo);
            return logInfo + " " + buildInfo;

        } catch (Exception e) {
            logger.info("Failed to build Jenkins job with jobName: " + jobName);
            e.printStackTrace();
            throw new BentenJenkinsException(e.getMessage());
        }
    }


    public String showConsoleLogForJobWithBuildNumber(String jobName, int buildNumber){
        logger.info("Getting Console Log for job: " + jobName + " with BuildNumber: " +buildNumber);
        try {
            logger.info("");

            String fullLog = jenkins.getJob(jobName).getBuildByNumber(buildNumber).details().getConsoleOutputText();
            return fullLog.substring(fullLog.length()-1000, fullLog.length()-1);
        }catch (Exception e) {
            logger.error("Failed to get Console Log for job: " + jobName + " with BuildNumber: " +buildNumber);
            e.printStackTrace();
            throw new BentenJenkinsException(e.getMessage());
        }
    }

    public int getLatestBuildNumber(String jobName){
        try{
            return jenkins.getJob(jobName).getLastBuild().getNumber();
        }catch (Exception e){
            e.printStackTrace();
            throw new BentenJenkinsException(e.getMessage());
        }
    }

    public List<JenkinsJobBuildParameter> getBuildParams(String jobName){
        try{
            String jobJson = jenkinsHttpClient.get(new URI(jenkins.getJob(jobName).getUrl()).getPath());
            logger.debug("Complete jobJson: " + jobJson);

            return parseJsonGetBuildParams(jobJson);
        }catch (Exception e){
            logger.error("Failed to get build parameters for job: " + jobName);
            e.printStackTrace();
            throw new BentenJenkinsException(e.getMessage());
        }
    }

    private List<JenkinsJobBuildParameter> parseJsonGetBuildParams(String jobJson){

        JSONArray jsonArray = JsonPath.read(jobJson, "$.actions[*].parameterDefinitions[*]");

        logger.debug("Size of the array: " + jsonArray.size());
        logger.debug("Param array: " + jsonArray);

        List<JenkinsJobBuildParameter> jenkinsJobBuildParameters = new ArrayList<JenkinsJobBuildParameter>();

        jsonArray.forEach(item -> {
            JenkinsJobBuildParameter jenkinsJobBuildParameter = new JenkinsJobBuildParameter();
            List<String> name = JsonPath.read(item,"$..name");
            List<String> defaultValue = JsonPath.read(item,"$..defaultParameterValue.value");
            List<String> choices = JsonPath.read(item,"$..choices[*]");

            logger.info("Name: " + name.get(0));
            logger.info("Default Value: " + defaultValue.get(0));
            logger.info("Choices: " + choices);
            jenkinsJobBuildParameter.setName(name.get(0));
            jenkinsJobBuildParameter.setDefaultValue(defaultValue.get(0));
            jenkinsJobBuildParameter.setChoices(choices);
            jenkinsJobBuildParameters.add(jenkinsJobBuildParameter);
        });
        return jenkinsJobBuildParameters;
    }

    private Map<String, String> constructJobParams(List<JenkinsJobBuildParameter> buildParams, String stringParams){
        Map<String, String> params = new HashMap<String, String>();

        for (JenkinsJobBuildParameter buildParam : buildParams) {
            params.put(buildParam.getName(), buildParam.getDefaultValue());
        }

        String[] jobParams = stringParams.split(" ");
        for (String param : jobParams) {
            String[] temp = param.split("=");

            if(params.containsKey(temp[0])){
                params.put(temp[0],temp[1]);
            } else {
                logger.info("Incorrect parameter input: " + temp[0]);
            }
        }

        return params;
    }

    public JenkinsServer getJenkins() {
        return jenkins;
    }

}
