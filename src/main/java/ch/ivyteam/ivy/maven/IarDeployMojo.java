/*
 * Copyright (C) 2016 AXON IVY AG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.ivyteam.ivy.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import ch.ivyteam.ivy.maven.engine.deploy.IvyProjectDeployer;
import ch.ivyteam.ivy.maven.engine.deploy.MarkerFileDeployer;

/**
 * Deploys an ivy-archive (IAR) to a running AXON.IVY Engine.
 * 
 * <p>Execution without a maven project is possible. E.g.</p>
 * <pre>com.axonivy.ivy.ci:project-build-plugin:6.1.0-SNAPSHOT:deploy-iar 
 * -DdeployIarFile=myProject.iar 
 * -DdeployEngineDirectory=c:/axonviy/engine
 * -DdeployToEngineApplication=theApp</pre>
 * 
 * 
 * @since 6.1.0
 */
@Mojo(name = IarDeployMojo.GOAL, requiresProject=false)
public class IarDeployMojo extends AbstractEngineMojo
{
  public static final String GOAL = "deploy-iar";
  
  /** The IAR to deploy. By default the packed IAR from the {@link IarPackagingMojo#GOAL} is used. */
  @Parameter(property="deployIarFile", defaultValue="${project.build.directory}/${project.artifactId}-${project.version}.iar")
  File deployIarFile;
  
  /** The path to the AXON.IVY Engine to which we deploy the IAR. <br/>
   * The path can reference a remote engine by using UNC paths e.g. <code>\\myRemoteHost\myEngineShare</code> */
  @Parameter(property="deployEngineDirectory", defaultValue="${"+ENGINE_DIRECTORY_PROPERTY+"}")
  File deployEngineDirectory;
  
  /** The name of an ivy application to which the IAR is deployed. */
  @Parameter(property="deployToEngineApplication", defaultValue="SYSTEM")
  String deployToEngineApplication;
  
  /** The auto deployment directory of the engine. Must match the ivy engine system property 'deployment.directory' */
  @Parameter(property="deployDirectory", defaultValue="deploy")
  String deployDirectory;
  
  /** The maximum amount of seconds that we wait for a deployment result from the engine */
  @Parameter(property="deployTimeoutInSeconds", defaultValue="30")
  Integer deployTimeoutInSeconds;
  
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException
  {
    if (!deployIarFile.exists())
    {
      getLog().warn("Skipping IAR deployment of '"+deployIarFile+"'. The file does not exist.");
      return;
    }
    File deployDir = getDeployDirectory();
    if (!deployDir.exists())
    {
      getLog().warn("Skipping IAR deployment to engine '"+deployEngineDirectory+"'. The directory '"+deployDir+"' does not exist.");
      return;
    }
    
    File uploadedIar = copyIarToEngine(deployDir);
    
    String iarPath = deployEngineDirectory.toPath().relativize(uploadedIar.toPath()).toString();
    IvyProjectDeployer deployer = new MarkerFileDeployer(deployEngineDirectory, deployTimeoutInSeconds);
    deployer.deployIar(iarPath, getLog());
  }

  private File getDeployDirectory()
  {
    if (deployEngineDirectory == null)
    { // re-use engine used to build
      deployEngineDirectory = getEngineDirectory();
    }
    if (Paths.get(deployDirectory).isAbsolute())
    {
      return new File(deployDirectory);
    }
    return new File(deployEngineDirectory, deployDirectory);
  }

  private File copyIarToEngine(File deployDir) throws MojoExecutionException
  {
    File deployApp = new File(deployDir, deployToEngineApplication);
    File targetIarFile = new File(deployApp, deployIarFile.getName());
    try
    {
      getLog().info("Uploading project "+targetIarFile);
      FileUtils.copyFile(deployIarFile, targetIarFile);
      return targetIarFile;
    }
    catch (IOException ex)
    {
      throw new MojoExecutionException("Upload of IAR '"+deployIarFile.getName()+"' failed.", ex);
    }
  }
  
}