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
import java.nio.file.Paths;
import java.nio.file.ReadOnlyFileSystemException;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.filtering.MavenFileFilter;

import ch.ivyteam.ivy.maven.engine.deploy.DeploymentOptionsFileFactory;
import ch.ivyteam.ivy.maven.engine.deploy.FileDeployer;
import ch.ivyteam.ivy.maven.engine.deploy.IvyDeployer;
import ch.ivyteam.ivy.maven.engine.deploy.YamlOptionsFactory;

/**
 * Deploys a single project (iar) or a full application (set of projects as zip) to a running AXON.IVY Engine.
 *
 * <p>Command line invocation is supported. E.g.</p>
 * <pre>mvn com.axonivy.ivy.ci:project-build-plugin:7.1.0:deploy-to-engine
 * -Divy.deploy.file=myProject.iar
 * -Divy.deploy.engine.dir=c:/axonivy/engine
 * -Divy.deploy.engine.app=Portal</pre>
 *
 * @since 7.1.0
 */
@Mojo(name = DeployToEngineMojo.GOAL, requiresProject=false)
public class DeployToEngineMojo extends AbstractEngineMojo
{
  static final String PROPERTY_IVY_DEPLOY_FILE = "ivy.deploy.file";

  public static final String GOAL = "deploy-to-engine";

  /** The file to deploy. Can either be a *.iar project file or a *.zip file containing a full application (set of projects). By default the packed IAR from the {@link IarPackagingMojo#GOAL} is used. */
  @Parameter(property=PROPERTY_IVY_DEPLOY_FILE, defaultValue="${project.build.directory}/${project.artifactId}-${project.version}.iar")
  File deployFile;

  /** The path to the AXON.IVY Engine to which we deploy the file. <br/>
   * The path can reference a remote engine by using UNC paths e.g. <code>\\myRemoteHost\myEngineShare</code> */
  @Parameter(property="ivy.deploy.engine.dir", defaultValue="${"+ENGINE_DIRECTORY_PROPERTY+"}")
  File deployEngineDirectory;

  /** The name of an ivy application to which the file is deployed. */
  @Parameter(property="ivy.deploy.engine.app", defaultValue="SYSTEM")
  String deployToEngineApplication;

  /** The auto deployment directory of the engine. Must match the ivy engine system property 'deployment.directory' */
  @Parameter(property="ivy.deploy.dir", defaultValue="deploy")
  String deployDirectory;

  /** The file that contains deployment options. <br/>
   *
   * Example options file content:
   * <pre><code>deployTestUsers: true
   *configuration:
   *  overwrite: true
   *  cleanup: REMOVE_UNUSED
   *target:
   *  version: RELEASED
   *  state: ACTIVE_AND_RELEASED</code></pre>
   *
   *  <p>Inside the options file you can use property placeholders. The options file may look like this:</p>
   *  <pre><code>deployTestUsers: ${ivy.deploy.test.users}
   *configuration:
   *  overwrite: true
   *  cleanup: REMOVE_UNUSED
   *target:
   *  version: AUTO
   *  state: ${ivy.deploy.target.state}</code></pre>
   *
   *  <p>All options in this file are optional. You only need to specify options that overwrite the default behavior.</p>
   *  <p>If configured, all Maven properties are ignored and only values in this file are used.</p>
   *
   * @see <a href="https://developer.axonivy.com/doc/7.1.latest/EngineGuideHtml/administration.html#administration.deployment.directory.options">Engine Guide</a>
   */
  @Parameter(property="ivy.deploy.options.file", required=false)
  File deployOptionsFile;

  /** The maximum amount of seconds that we wait for a deployment result from the engine */
  @Parameter(property="ivy.deploy.timeout.seconds", defaultValue="30")
  Integer deployTimeoutInSeconds;

  /** Set to <code>true</code> to skip the deployment to the engine. */
  @Parameter(defaultValue="false", property="ivy.deploy.skip")
  boolean skipDeploy;

  /** If set to <code>true</code> then test users defined in the projects are deployed to the engine.
   * Only works if the current security system allows to create users.
   * Should only be used for testing. */
  @Parameter(property="ivy.deploy.test.users", defaultValue="false")
  public boolean deployTestUsers;

  /** If set to <code>true</code> then configurations (global variables, external database, web services, REST clients)
   * defined in the deployed projects overwrite the configurations that are already configured on the engine. */
  @Parameter(property="ivy.deploy.configuration.overwrite", defaultValue="false")
  public boolean deployConfigOverwrite;

  /**
   * Controls whether all configurations (global variables, external database, web services, REST clients) should be cleaned.
   *
   * <p>Possible values:</p>
   * <ul>
   *    <li><code>DISABLED</code>: all configurations will be kept on the application.</li>
   *    <li><code>REMOVE_UNUSED</code>: all configurations that are not used by any projects deployed on the application will be removed after the deployment.</li>
   *    <li><code>REMOVE_ALL</code>: all configurations of the application are removed before the deployment.<br>
   *    <strong>Should only be used for development or test engines.<br>
   *    Do not use in productive systems because it could break already deployed projects! </strong></li>
   * </ul>
   */
  @Parameter(property="ivy.deploy.configuration.cleanup", defaultValue=DefaultDeployOptions.CLEANUP_DISABLED)
  public String deployConfigCleanup;

  /**
   * The target version controls on which process model version (PMV) a project is re-deployed.
   *
   * <p>Matching:</p>
   * <ul>
   *    <li>In all cases the library identifier (group id and project/artifact id) of the PMV and the project has to be equal.</li>
   *    <li>If multiple PMVs match the target version then the PMV with the highest library version is chosen.</li>
   *    <li>If no PMV matches the target version then a new PMV is created and the project is deployed to the new PMV.</li>
   * </ul>
   *
   * <p>Possible values:</p>
   * <ul>
   *    <li><code>AUTO</code>: a project is re-deployed if the version of the PMV is equal to the project's version.</li>
   *    <li><code>RELEASED</code>: a project is re-deployed to the released PMV. The version of the PMV and the project does not matter</li>
   *    <li>Maven version range: a project is re-deployed if the version of the PMV matches the given range. Some samples:
   *       <ul>
   *         <li><code>,</code> - Matches all version.</li>
   *         <li><code>,2.5]</code> - Matches every version up to 2.5 inclusive.</li>
   *         <li><code>(2.5,</code> - Matches every version from 2.5 exclusive.</li>
   *         <li><code>[2.0,3.0)</code> - Matches every version from 2.0 inclusive up to 3.0 exclusive.</li>
   *         <li><code>2.5</code> - Matches every version from 2.5 inclusive.</li>
   *       </ul>
   *    </li>
   * </ul>
   */
  @Parameter(property="ivy.deploy.target.version", defaultValue=DefaultDeployOptions.VERSION_AUTO)
  public String deployTargetVersion;

  /**
   * The target state of all process model versions (PMVs) of the deployed projects.
   *
   * <ul>
   *   <li><code>ACTIVE_AND_RELEASED</code>: PMVs are activated and released after the deployment</li>
   *   <li><code>ACTIVE</code>: PMVs are activated but not released after the deployment</li>
   *   <li><code>INACTIVE</code>: PMVs are neither activated nor released after the deployment</li>
   * </ul>
   */
  @Parameter(property="ivy.deploy.target.state", defaultValue=DefaultDeployOptions.STATE_ACTIVE_AND_RELEASED)
  public String deployTargetState;

  /**
   * The target file format as which the project will be deployed into the process model version (PMV).
   *
   * <ul>
   *    <li><code>AUTO</code>: Keep the format of the origin project file if possible. Deploys IAR or ZIP projects into a ZIP process model version. <br>
   *        But if the target PMV already exists as expanded directory, the new version will be expanded as well.</li>
   *    <li><code>EXPANDED</code>: Enforce the deployment of a project as expanded file directory.<br>
   *        This is recommended for projects that change the project files at runtime. E.g. projects that use the Content Management (CMS) write API.<br>
   *        The expanded format behaves exactly like projects deployed with Axon.ivy 7.0 or older. You might choose to deploy expanded projects in order to avoid {@link ReadOnlyFileSystemException} at runtime.<br>
   *        <strong>Warning</strong>: Expanded projects will perform slower at runtime and are therefore not recommended.</li>
   * </ul>
   * */
  @Parameter(property = "ivy.deploy.target.file.format", defaultValue = DefaultDeployOptions.FILE_FORMAT_AUTO)
  public String deployTargetFileFormat;

  @Component
  private MavenFileFilter fileFilter;

  @Parameter(property = "project", required = false, readonly = true)
  MavenProject project;

  @Parameter(property = "session", required = true, readonly = true)
  MavenSession session;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException
  {
    if (skipDeploy)
    {
      getLog().info("Skipping deployment to engine.");
      return;
    }
    warnDeprectadIarProperty();
    if (!deployFile.exists())
    {
      getLog().warn("Skipping deployment of '"+deployFile+"' to engine. The file does not exist.");
      return;
    }
    File deployDir = getDeployDirectory();
    if (!deployDir.exists())
    {
      getLog().warn("Skipping deployment to engine '"+deployEngineDirectory+"'. The directory '"+deployDir+"' does not exist.");
      return;
    }

    File targetDeployableFile = createTargetDeployableFile(deployDir);
    String deployablePath = deployDir.toPath().relativize(targetDeployableFile.toPath()).toString();

    File resolvedOptionsFile = createDeployOptionsFile();
    IvyDeployer deployer = new FileDeployer(deployDir, resolvedOptionsFile, deployTimeoutInSeconds, deployFile, targetDeployableFile);
    deployer.deploy(deployablePath, getLog());
  }

  private File getDeployDirectory() throws MojoExecutionException
  {
    if (deployEngineDirectory == null)
    { // re-use engine used to build
      deployEngineDirectory = identifyAndGetEngineDirectory();
    }
    if (Paths.get(deployDirectory).isAbsolute())
    {
      return new File(deployDirectory);
    }
    return new File(deployEngineDirectory, deployDirectory);
  }

  private File createTargetDeployableFile(File deployDir)
  {
    File deployApp = new File(deployDir, deployToEngineApplication);
    File targetDeployableFile = new File(deployApp, deployFile.getName());
    return targetDeployableFile;
  }

  @SuppressWarnings("deprecation")
  private void warnDeprectadIarProperty()
  {
    String legacyIarFileProperty = System.getProperty(IarDeployMojo.PROPERTY_IVY_DEPLOY_IAR_FILE);
    if (legacyIarFileProperty != null)
    {
      getLog().warn("Ignoring deprecated property '"+IarDeployMojo.PROPERTY_IVY_DEPLOY_IAR_FILE+"' with value '"+legacyIarFileProperty+"'.");
      getLog().warn("Please migrate to the new property '"+PROPERTY_IVY_DEPLOY_FILE+"'.");
    }
  }

  private File createDeployOptionsFile() throws MojoExecutionException
  {
    DeploymentOptionsFileFactory optionsFileFactory = new DeploymentOptionsFileFactory(deployFile);
    if (deployOptionsFile != null)
    {
      return optionsFileFactory.createFromTemplate(deployOptionsFile, project, session, fileFilter);
    }

    String yamlOptions = YamlOptionsFactory.generate(this);
    if (StringUtils.isNotBlank(yamlOptions))
    {
      return optionsFileFactory.createFromConfiguration(yamlOptions);
    }
    return null;
  }

  public static interface DefaultDeployOptions
  {
    String CLEANUP_DISABLED = "DISABLED";
    String VERSION_AUTO = "AUTO";
    String STATE_ACTIVE_AND_RELEASED = "ACTIVE_AND_RELEASED";
    String FILE_FORMAT_AUTO = "AUTO";
  }

}
