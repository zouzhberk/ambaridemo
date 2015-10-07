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

import sys
import fileinput
import os
import ambari_simplejson as json # simplejson is much faster comparing to Python 2.6 json module and has the same functions set.
import urllib2, base64, httplib
from StringIO import StringIO as BytesIO
from datetime import datetime
from resource_management.core.resources.system import File, Directory, Execute
from resource_management.libraries.resources.xml_config import XmlConfig
from resource_management.libraries.resources.modify_properties_file import ModifyPropertiesFile
from resource_management.core.source import DownloadSource, InlineTemplate
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.ranger_functions import Rangeradmin
from resource_management.core.utils import PasswordString
from resource_management.core.shell import as_sudo

def setup_kms_db():
  import params

  if params.has_ranger_admin:

    File(params.downloaded_custom_connector,
      content = DownloadSource(params.driver_curl_source),
      mode = 0644
    )

    Directory(params.java_share_dir,
      mode=0755,
      recursive=True,
      cd_access="a"
    )
    
    if params.db_flavor.lower() != 'sqla':
      Execute(('cp', '--remove-destination', params.downloaded_custom_connector, params.driver_curl_target),
          path=["/bin", "/usr/bin/"],
          sudo=True)

      File(params.driver_curl_target, mode=0644)

    Directory(os.path.join(params.kms_home, 'ews', 'lib'),
      mode=0755
    )
    
    if params.db_flavor.lower() == 'sqla':
      Execute(('tar', '-xvf', params.downloaded_custom_connector, '-C', params.tmp_dir), sudo = True)

      Execute(('cp', '--remove-destination', params.jar_path_in_archive, os.path.join(params.kms_home, 'ews', 'webapp', 'lib')),
        path=["/bin", "/usr/bin/"],
        sudo=True)

      Directory(params.jdbc_libs_dir,
        cd_access="a",
        recursive=True)

      Execute(as_sudo(['yes', '|', 'cp', params.libs_path_in_archive, params.jdbc_libs_dir], auto_escape=False),
        path=["/bin", "/usr/bin/"])
    else:
      Execute(('cp', '--remove-destination', params.downloaded_custom_connector, os.path.join(params.kms_home, 'ews', 'webapp', 'lib')),
        path=["/bin", "/usr/bin/"],
        sudo=True)

    File(os.path.join(params.kms_home, 'ews', 'webapp', 'lib', params.jdbc_jar_name), mode=0644)

    ModifyPropertiesFile(format("/usr/hdp/current/ranger-kms/install.properties"),
      properties = params.config['configurations']['kms-properties'],
      owner = params.kms_user
    )

    if params.db_flavor.lower() == 'sqla':
      ModifyPropertiesFile(format("{kms_home}/install.properties"),
        properties = {'SQL_CONNECTOR_JAR': format('{kms_home}/ews/webapp/lib/{jdbc_jar_name}')},
        owner = params.kms_user,
      )

    env_dict = {'RANGER_KMS_HOME':params.kms_home, 'JAVA_HOME': params.java_home}
    if params.db_flavor.lower() == 'sqla':
      env_dict = {'RANGER_KMS_HOME':params.kms_home, 'JAVA_HOME': params.java_home, 'LD_LIBRARY_PATH':params.ld_library_path}

    dba_setup = format('ambari-python-wrap {kms_home}/dba_script.py -q')
    db_setup = format('ambari-python-wrap {kms_home}/db_setup.py')

    Execute(dba_setup, environment=env_dict, logoutput=True, user=params.kms_user)
    Execute(db_setup, environment=env_dict, logoutput=True, user=params.kms_user)

def setup_java_patch():
  import params

  if params.has_ranger_admin:

    setup_java_patch = format('ambari-python-wrap {kms_home}/db_setup.py -javapatch')

    env_dict = {'RANGER_KMS_HOME':params.kms_home, 'JAVA_HOME': params.java_home}
    if params.db_flavor.lower() == 'sqla':
      env_dict = {'RANGER_KMS_HOME':params.kms_home, 'JAVA_HOME': params.java_home, 'LD_LIBRARY_PATH':params.ld_library_path}

    Execute(setup_java_patch, environment=env_dict, logoutput=True, user=params.kms_user)

    kms_lib_path = format('{kms_home}/ews/webapp/lib/')
    files = os.listdir(kms_lib_path)
    hadoop_jar_files = []

    for x in files:
      if x.startswith('hadoop-common') and x.endswith('.jar'):
        hadoop_jar_files.append(x)

    if len(hadoop_jar_files) != 0:
      for f in hadoop_jar_files:
        Execute((format('{java_home}/bin/jar'),'-uf', format('{kms_home}/ews/webapp/lib/{f}'), format('{kms_home}/ews/webapp/META-INF/services/org.apache.hadoop.crypto.key.KeyProviderFactory')),
          user=params.kms_user)

        File(format('{kms_home}/ews/webapp/lib/{f}'), owner=params.kms_user, group=params.kms_group)


def do_keystore_setup(cred_provider_path, credential_alias, credential_password): 
  import params

  if cred_provider_path is not None:
    cred_setup = params.cred_setup_prefix + ('-f', cred_provider_path, '-k', credential_alias, '-v', PasswordString(credential_password), '-c', '1')
    Execute(cred_setup, 
            environment={'JAVA_HOME': params.java_home}, 
            logoutput=True, 
            sudo=True,
    )

    File(cred_provider_path,
      owner = params.kms_user,
      group = params.kms_group,
      mode = 0640
    )

def kms():
  import params

  if params.has_ranger_admin:

    Directory(params.kms_conf_dir,
      owner = params.kms_user,
      group = params.kms_group,
      recursive = True
    )

    if params.xa_audit_db_is_enabled:
      File(params.downloaded_connector_path,
        content = DownloadSource(params.driver_source),
        mode = 0644
      )

      Execute(('cp', '--remove-destination', params.downloaded_connector_path, params.driver_target),
          path=["/bin", "/usr/bin/"],
          sudo=True)

      File(params.driver_target, mode=0644)

    Directory(os.path.join(params.kms_home, 'ews', 'webapp', 'WEB-INF', 'classes', 'lib'),
        mode=0755,
        owner=params.kms_user,
        group=params.kms_group        
      )

    Execute(('cp',format('{kms_home}/ranger-kms-initd'),'/etc/init.d/ranger-kms'),
    not_if=format('ls /etc/init.d/ranger-kms'),
    only_if=format('ls {kms_home}/ranger-kms-initd'),
    sudo=True)

    File('/etc/init.d/ranger-kms',
      mode = 0755
    )

    Execute(('chown','-R',format('{kms_user}:{kms_group}'), format('{kms_home}/')), sudo=True)

    Directory(params.kms_log_dir,
      owner = params.kms_user,
      group = params.kms_group
    )

    Execute(('ln','-sf', format('{kms_home}/ranger-kms'),'/usr/bin/ranger-kms'),
      not_if=format('ls /usr/bin/ranger-kms'),
      only_if=format('ls {kms_home}/ranger-kms'),
      sudo=True)

    File('/usr/bin/ranger-kms', mode = 0755)

    Execute(('ln','-sf', format('{kms_home}/ranger-kms'),'/usr/bin/ranger-kms-services.sh'),
      not_if=format('ls /usr/bin/ranger-kms-services.sh'),
      only_if=format('ls {kms_home}/ranger-kms'),
      sudo=True)

    File('/usr/bin/ranger-kms-services.sh', mode = 0755)

    Execute(('ln','-sf', format('{kms_home}/ranger-kms-initd'),format('{kms_home}/ranger-kms-services.sh')),
      not_if=format('ls {kms_home}/ranger-kms-services.sh'),
      only_if=format('ls {kms_home}/ranger-kms-initd'),
      sudo=True)

    File(format('{kms_home}/ranger-kms-services.sh'), mode = 0755)

    Directory(params.kms_log_dir,
      owner = params.kms_user,
      group = params.kms_group,
      mode = 0775
    )

    do_keystore_setup(params.credential_provider_path, params.jdbc_alias, params.db_password)
    do_keystore_setup(params.credential_provider_path, params.masterkey_alias, params.kms_master_key_password)

    XmlConfig("dbks-site.xml",
      conf_dir=params.kms_conf_dir,
      configurations=params.config['configurations']['dbks-site'],
      configuration_attributes=params.config['configuration_attributes']['dbks-site'],
      owner=params.kms_user,
      group=params.kms_group,
      mode=0644
    )

    XmlConfig("ranger-kms-site.xml",
      conf_dir=params.kms_conf_dir,
      configurations=params.config['configurations']['ranger-kms-site'],
      configuration_attributes=params.config['configuration_attributes']['ranger-kms-site'],
      owner=params.kms_user,
      group=params.kms_group,
      mode=0644
    )

    XmlConfig("kms-site.xml",
      conf_dir=params.kms_conf_dir,
      configurations=params.config['configurations']['kms-site'],
      configuration_attributes=params.config['configuration_attributes']['kms-site'],
      owner=params.kms_user,
      group=params.kms_group,
      mode=0644
    )

    File(os.path.join(params.kms_conf_dir, "kms-log4j.properties"),
      owner=params.kms_user,
      group=params.kms_group,
      content=params.kms_log4j,
      mode=0644
    )

def enable_kms_plugin():

  import params

  if params.has_ranger_admin:

    ranger_adm_obj = Rangeradmin(url=params.policymgr_mgr_url)
    response_code, response_recieved = ranger_adm_obj.check_ranger_login_urllib2(params.policymgr_mgr_url + '/login.jsp', 'test:test')
    if response_code is not None and response_code == 200:
      ambari_ranger_admin, ambari_ranger_password = ranger_adm_obj.create_ambari_admin_user(params.ambari_ranger_admin, params.ambari_ranger_password, params.admin_uname_password)
      ambari_username_password_for_ranger = ambari_ranger_admin + ':' + ambari_ranger_password
    else:
      raise Fail('Ranger service is not started on given host')   

    if ambari_ranger_admin != '' and ambari_ranger_password != '':
      get_repo_flag = get_repo(params.policymgr_mgr_url, params.repo_name, ambari_username_password_for_ranger)
      if not get_repo_flag:
        create_repo(params.policymgr_mgr_url, json.dumps(params.kms_ranger_plugin_repo), ambari_username_password_for_ranger)
    else:
      raise Fail('Ambari admin username and password not available')

    current_datetime = datetime.now()

    File(format('{kms_conf_dir}/ranger-security.xml'),
      owner = params.kms_user,
      group = params.kms_group,
      mode = 0644,
      content = InlineTemplate(format('<ranger>\n<enabled>{current_datetime}</enabled>\n</ranger>'))
    )

    Directory([os.path.join('/etc', 'ranger', params.repo_name), os.path.join('/etc', 'ranger', params.repo_name, 'policycache')],
      owner = params.kms_user,
      group = params.kms_group,
      mode=0775,
      recursive = True
    )
    
    File(os.path.join('/etc', 'ranger', params.repo_name, 'policycache',format('kms_{repo_name}.json')),
      owner = params.kms_user,
      group = params.kms_group,
      mode = 0644        
    )

    XmlConfig("ranger-kms-audit.xml",
      conf_dir=params.kms_conf_dir,
      configurations=params.config['configurations']['ranger-kms-audit'],
      configuration_attributes=params.config['configuration_attributes']['ranger-kms-audit'],
      owner=params.kms_user,
      group=params.kms_group,
      mode=0744)

    XmlConfig("ranger-kms-security.xml",
      conf_dir=params.kms_conf_dir,
      configurations=params.config['configurations']['ranger-kms-security'],
      configuration_attributes=params.config['configuration_attributes']['ranger-kms-security'],
      owner=params.kms_user,
      group=params.kms_group,
      mode=0744)

    XmlConfig("ranger-policymgr-ssl.xml",
      conf_dir=params.kms_conf_dir,
      configurations=params.config['configurations']['ranger-kms-policymgr-ssl'],
      configuration_attributes=params.config['configuration_attributes']['ranger-kms-policymgr-ssl'],
      owner=params.kms_user,
      group=params.kms_group,
      mode=0744)

    if params.xa_audit_db_is_enabled:
      cred_setup = params.cred_setup_prefix + ('-f', params.credential_file, '-k', 'auditDBCred', '-v', PasswordString(params.xa_audit_db_password), '-c', '1')
      Execute(cred_setup, environment={'JAVA_HOME': params.java_home}, logoutput=True, sudo=True)

    cred_setup = params.cred_setup_prefix + ('-f', params.credential_file, '-k', 'sslKeyStore', '-v', PasswordString(params.ssl_keystore_password), '-c', '1')
    Execute(cred_setup, environment={'JAVA_HOME': params.java_home}, logoutput=True, sudo=True)

    cred_setup = params.cred_setup_prefix + ('-f', params.credential_file, '-k', 'sslTrustStore', '-v', PasswordString(params.ssl_truststore_password), '-c', '1')
    Execute(cred_setup, environment={'JAVA_HOME': params.java_home}, logoutput=True, sudo=True)

    File(params.credential_file,
      owner = params.kms_user,
      group = params.kms_group,
      mode = 0640
      )
  

def create_repo(url, data, usernamepassword):
  try:
    base_url = url + '/service/public/v2/api/service'
    base64string = base64.encodestring('{0}'.format(usernamepassword)).replace('\n', '')
    headers = {
      'Accept': 'application/json',
      "Content-Type": "application/json"
    }
    request = urllib2.Request(base_url, data, headers)
    request.add_header("Authorization", "Basic {0}".format(base64string))
    result = urllib2.urlopen(request)
    response_code = result.getcode()
    response = json.loads(json.JSONEncoder().encode(result.read()))
    if response_code == 200:
      Logger.info('Repository created Successfully')
    else:
      Logger.info('Repository not created')
  except urllib2.URLError, e:
    raise Fail('Repository creation failed, {0}'.format(str(e)))  

def get_repo(url, name, usernamepassword):
  try:
    base_url = url + '/service/public/v2/api/service?serviceName=' + name + '&serviceType=kms&isEnabled=true'
    request = urllib2.Request(base_url)
    base64string = base64.encodestring(usernamepassword).replace('\n', '')
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    request.add_header("Authorization", "Basic {0}".format(base64string))
    result = urllib2.urlopen(request)
    response_code = result.getcode()
    response = json.loads(result.read())
    if response_code == 200 and len(response) > 0:
      for repo in response:
        if repo.get('name') == name and repo.has_key('name'):
          Logger.info('KMS repository exist')
          return True
        else:
          Logger.info('KMS repository doesnot exist')
          return False
    else:
      Logger.info('KMS repository doesnot exist')
      return False
  except urllib2.URLError, e:
    raise Fail('Get repository failed, {0}'.format(str(e))) 
