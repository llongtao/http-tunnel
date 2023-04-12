/*
 * htunnel - A simple HTTP tunnel
 * https://github.com/nicolas-dutertry/htunnel
 *
 * Written by Nicolas Dutertry.
 *
 * This file is provided under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.dutertry.htunnel.server.controller;

import com.dutertry.htunnel.server.config.ResourceConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * @author Nicolas Dutertry
 */
@Slf4j
@RestController
public class TunnelController {

    @Resource
    ResourceConfig resourceConfig;


    @GetMapping(value = "/resource")
    public Map<String, String> resource() {
        return resourceConfig.getMap();
    }

}
