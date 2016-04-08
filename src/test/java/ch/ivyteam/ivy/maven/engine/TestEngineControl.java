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

package ch.ivyteam.ivy.maven.engine;

import static org.fest.assertions.api.Assertions.assertThat;

import org.junit.Rule;
import org.junit.Test;

import ch.ivyteam.ivy.maven.BaseEngineProjectMojoTest;
import ch.ivyteam.ivy.maven.RunnableEngineMojoRule;
import ch.ivyteam.ivy.maven.StopTestEngineMojo;
import ch.ivyteam.ivy.maven.engine.EngineControl.EngineState;
import ch.ivyteam.ivy.maven.log.LogCollector;

public class TestEngineControl extends BaseEngineProjectMojoTest
{
  @Rule
  public RunnableEngineMojoRule<StopTestEngineMojo> rule = new RunnableEngineMojoRule<StopTestEngineMojo>(StopTestEngineMojo.GOAL);
  
  @Test
  public void resolveEngineState()
  {
    EngineControl controller = rule.getMojo().createEngineController();
    assertThat(controller.state()).isNotNull();
  }
  
  @Test
  public void stopNotRunningEngine() throws Exception
  {
    EngineControl controller = rule.getMojo().createEngineController();
    controller.stop();
    assertThat(controller.state()).isEqualTo(EngineState.STOPPED);
  }
  
  @Test
  public void startAndStop() throws Exception
  {
    LogCollector log = new LogCollector();
    rule.getMojo().setLog(log);
    EngineControl controller = rule.getMojo().createEngineController();
    assertThat(controller.state()).isEqualTo(EngineState.STOPPED);
    controller.start();
    assertThat(controller.state()).isEqualTo(EngineState.RUNNING);
    controller.stop();
    assertThat(controller.state()).isEqualTo(EngineState.STOPPED);
    assertThat(log.getErrors()).isEmpty();
  }
  
}
