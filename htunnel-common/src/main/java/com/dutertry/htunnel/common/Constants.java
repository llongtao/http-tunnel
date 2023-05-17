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
package com.dutertry.htunnel.common;

/**
 * @author Nicolas Dutertry
 */
public interface Constants {
    String HEADER_CONNECTION_ID = "X-HTUNNEL-ID";

    String CRT = "ORxLWo^BR5$BKKqGBVqu050B#RzLfPAQ";

    String USERNAME_KEY = "username";
    String PASSWORD_KEY = "password";
    String RESOURCE_KEY = "resource";

    String SOCKET_CHANNEL_KEY = "SOCKET_CHANNEL";

    String UN_AUTH_MSG = "[unAuth]";

    String ERR_MSG_PRE = "[error]";

    String TRACE_ID_KEY = "TRACE_ID";

}
