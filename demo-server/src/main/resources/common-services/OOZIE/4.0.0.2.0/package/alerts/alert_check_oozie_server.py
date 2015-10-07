#!/usr/bin/env python

"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""
from resource_management.core.environment import Environment
from resource_management.core.resources import Execute
from resource_management.core.shell import call
from resource_management.libraries.functions import format
from resource_management.libraries.functions import get_kinit_path
from resource_management.libraries.functions import get_klist_path
from ambari_commons.os_check import OSConst, OSCheck
from ambari_commons.os_family_impl import OsFamilyFuncImpl, OsFamilyImpl
from urlparse import urlparse
import os
import re

RESULT_CODE_OK = 'OK'
RESULT_CODE_CRITICAL = 'CRITICAL'
RESULT_CODE_UNKNOWN = 'UNKNOWN'

if OSCheck.is_windows_family():
  OOZIE_ENV_HTTPS_RE = r"set\s+OOZIE_HTTPS_PORT=(\d+)"
else:
  OOZIE_ENV_HTTPS_RE = r"export\s+OOZIE_HTTPS_PORT=(\d+)"

# The configured Kerberos executable search paths, if any
KERBEROS_EXECUTABLE_SEARCH_PATHS_KEY = '{{kerberos-env/executable_search_paths}}'

OOZIE_URL_KEY = '{{oozie-site/oozie.base.url}}'
SECURITY_ENABLED = '{{cluster-env/security_enabled}}'
OOZIE_PRINCIPAL = '{{cluster-env/smokeuser_principal_name}}'
OOZIE_KEYTAB = '{{cluster-env/smokeuser_keytab}}'
OOZIE_USER = '{{oozie-env/oozie_user}}'
OOZIE_CONF_DIR = '/usr/hdp/current/oozie-server/conf'
OOZIE_CONF_DIR_LEGACY = '/etc/oozie/conf'
OOZIE_HTTPS_PORT = '{{oozie-site/oozie.https.port}}'
OOZIE_ENV_CONTENT = '{{oozie-env/content}}'

SMOKEUSER_KEYTAB_KEY = '{{cluster-env/smokeuser_keytab}}'
SMOKEUSER_PRINCIPAL_KEY = '{{cluster-env/smokeuser_principal_name}}'
SMOKEUSER_KEY = '{{cluster-env/smokeuser}}'

# default keytab location
SMOKEUSER_KEYTAB_SCRIPT_PARAM_KEY = 'default.smoke.keytab'
SMOKEUSER_KEYTAB_DEFAULT = '/etc/security/keytabs/smokeuser.headless.keytab'

# default smoke principal
SMOKEUSER_PRINCIPAL_SCRIPT_PARAM_KEY = 'default.smoke.principal'
SMOKEUSER_PRINCIPAL_DEFAULT = 'ambari-qa@EXAMPLE.COM'

# default smoke user
SMOKEUSER_DEFAULT = 'ambari-qa'

class KerberosPropertiesNotFound(Exception): pass

@OsFamilyFuncImpl(os_family=OSConst.WINSRV_FAMILY)
def get_tokens():
  """
  Returns a tuple of tokens in the format {{site/property}} that will be used
  to build the dictionary passed into execute
  """
  return (OOZIE_URL_KEY,)

@OsFamilyFuncImpl(os_family=OsFamilyImpl.DEFAULT)
def get_tokens():
  """
  Returns a tuple of tokens in the format {{site/property}} that will be used
  to build the dictionary passed into execute
  """
  return (OOZIE_URL_KEY, SMOKEUSER_PRINCIPAL_KEY, SECURITY_ENABLED, SMOKEUSER_KEYTAB_KEY, KERBEROS_EXECUTABLE_SEARCH_PATHS_KEY,
          SMOKEUSER_KEY, OOZIE_HTTPS_PORT, OOZIE_ENV_CONTENT)

@OsFamilyFuncImpl(os_family=OSConst.WINSRV_FAMILY)
def get_check_command(oozie_url, host_name, configurations):
  from resource_management.libraries.functions import reload_windows_env
  reload_windows_env()
  oozie_home = os.environ['OOZIE_HOME']
  oozie_cmd = os.path.join(oozie_home, 'bin', 'oozie.cmd')
  command = format("cmd /c {oozie_cmd} admin -oozie {oozie_url} -status")
  return (command, None, None)

@OsFamilyFuncImpl(os_family=OsFamilyImpl.DEFAULT)
def get_check_command(oozie_url, host_name, configurations, parameters):
  kerberos_env = None

  smokeuser = SMOKEUSER_DEFAULT
  if SMOKEUSER_KEY in configurations:
    smokeuser = configurations[SMOKEUSER_KEY]

  security_enabled = False
  if SECURITY_ENABLED in configurations:
    security_enabled = str(configurations[SECURITY_ENABLED]).upper() == 'TRUE'

  if security_enabled:
    # defaults
    smokeuser_keytab = SMOKEUSER_KEYTAB_DEFAULT
    smokeuser_principal = SMOKEUSER_PRINCIPAL_DEFAULT

    # check script params
    if SMOKEUSER_PRINCIPAL_SCRIPT_PARAM_KEY in parameters:
      smokeuser_principal = parameters[SMOKEUSER_PRINCIPAL_SCRIPT_PARAM_KEY]
    if SMOKEUSER_KEYTAB_SCRIPT_PARAM_KEY in parameters:
      smokeuser_keytab = parameters[SMOKEUSER_KEYTAB_SCRIPT_PARAM_KEY]

    # check configurations last as they should always take precedence
    if SMOKEUSER_PRINCIPAL_KEY in configurations:
      smokeuser_principal = configurations[SMOKEUSER_PRINCIPAL_KEY]
    if SMOKEUSER_KEYTAB_KEY in configurations:
      smokeuser_keytab = configurations[SMOKEUSER_KEYTAB_KEY]

    # Create the kerberos credentials cache (ccache) file and set it in the environment to use
    # when executing curl
    env = Environment.get_instance()
    ccache_file = "{0}{1}oozie_alert_cc_{2}".format(env.tmp_dir, os.sep, os.getpid())
    kerberos_env = {'KRB5CCNAME': ccache_file}

    # Get the configured Kerberos executable search paths, if any
    kerberos_executable_search_paths = None
    if KERBEROS_EXECUTABLE_SEARCH_PATHS_KEY in configurations:
      kerberos_executable_search_paths = configurations[KERBEROS_EXECUTABLE_SEARCH_PATHS_KEY]

    klist_path_local = get_klist_path(kerberos_executable_search_paths)
    klist_command = format("{klist_path_local} -s {ccache_file}")

    # Determine if we need to kinit by testing to see if the relevant cache exists and has
    # non-expired tickets.  Tickets are marked to expire after 5 minutes to help reduce the number
    # it kinits we do but recover quickly when keytabs are regenerated
    return_code, _ = call(klist_command, user=smokeuser)
    if return_code != 0:
      kinit_path_local = get_kinit_path(kerberos_executable_search_paths)
      kinit_command = format("{kinit_path_local} -l 5m -kt {smokeuser_keytab} {smokeuser_principal}; ")

      # kinit
      Execute(kinit_command, environment=kerberos_env, user=smokeuser)

  # oozie configuration directory uses a symlink when > HDP 2.2
  oozie_config_directory = OOZIE_CONF_DIR_LEGACY
  if os.path.exists(OOZIE_CONF_DIR):
    oozie_config_directory = OOZIE_CONF_DIR

  command = "source {0}/oozie-env.sh ; oozie admin -oozie {1} -status".format(
    oozie_config_directory, oozie_url)

  return (command, kerberos_env, smokeuser)

def execute(configurations={}, parameters={}, host_name=None):
  """
  Returns a tuple containing the result code and a pre-formatted result label

  Keyword arguments:
  configurations (dictionary): a mapping of configuration key to value
  parameters (dictionary): a mapping of script parameter key to value
  host_name (string): the name of this host where the alert is running
  """

  if configurations is None:
    return (RESULT_CODE_UNKNOWN, ['There were no configurations supplied to the script.'])

  if not OOZIE_URL_KEY in configurations:
    return (RESULT_CODE_UNKNOWN, ['The Oozie URL is a required parameter.'])

  # use localhost on Windows, 0.0.0.0 on others; 0.0.0.0 means bind to all
  # interfaces, which doesn't work on Windows
  localhost_address = 'localhost' if OSCheck.get_os_family() == OSConst.WINSRV_FAMILY else '0.0.0.0'

  https_port = None
  # try to get https port form oozie-env content
  if OOZIE_ENV_CONTENT in configurations:
    for line in configurations[OOZIE_ENV_CONTENT].splitlines():
      result = re.match(OOZIE_ENV_HTTPS_RE, line)

      if result is not None:
        https_port = result.group(1)
  # or from oozie-site.xml
  if https_port is None and OOZIE_HTTPS_PORT in configurations:
    https_port = configurations[OOZIE_HTTPS_PORT]

  oozie_url = configurations[OOZIE_URL_KEY]

  # construct proper url for https
  if https_port is not None:
    parsed_url = urlparse(oozie_url)
    oozie_url = oozie_url.replace(parsed_url.scheme, "https")
    if parsed_url.port is None:
      oozie_url.replace(parsed_url.hostname, ":".join([parsed_url.hostname, str(https_port)]))
    else:
      oozie_url = oozie_url.replace(str(parsed_url.port), str(https_port))

  # https will not work with localhost address, we need put fqdn
  if https_port is None:
    oozie_url = oozie_url.replace(urlparse(oozie_url).hostname, localhost_address)

  try:
    command, env, smokeuser = get_check_command(oozie_url, host_name, configurations, parameters)
    # execute the command
    Execute(command, environment=env, user=smokeuser)

    return (RESULT_CODE_OK, ["Successful connection to {0}".format(oozie_url)])
  except KerberosPropertiesNotFound, ex:
    return (RESULT_CODE_UNKNOWN, [str(ex)])
  except Exception, ex:
    return (RESULT_CODE_CRITICAL, [str(ex)])
