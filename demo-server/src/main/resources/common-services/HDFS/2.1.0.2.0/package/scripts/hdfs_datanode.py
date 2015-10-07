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
import os
from resource_management import *
from resource_management.libraries.functions.dfs_datanode_helper import handle_dfs_data_dir
from utils import service
from ambari_commons.os_family_impl import OsFamilyImpl, OsFamilyFuncImpl
from ambari_commons import OSConst


def create_dirs(data_dir, params):
  """
  :param data_dir: The directory to create
  :param params: parameters
  """
  Directory(data_dir,
            recursive=True,
            cd_access="a",
            mode=0755,
            owner=params.hdfs_user,
            group=params.user_group,
            ignore_failures=True
  )

@OsFamilyFuncImpl(os_family=OsFamilyImpl.DEFAULT)
def datanode(action=None):
  if action == "configure":
    import params
    Directory(params.dfs_domain_socket_dir,
              recursive=True,
              mode=0751,
              owner=params.hdfs_user,
              group=params.user_group)

    if not os.path.isdir(os.path.dirname(params.data_dir_mount_file)):
      Directory(os.path.dirname(params.data_dir_mount_file),
                recursive=True,
                mode=0755,
                owner=params.hdfs_user,
                group=params.user_group)

    data_dir_to_mount_file_content = handle_dfs_data_dir(create_dirs, params)
    File(params.data_dir_mount_file,
         owner=params.hdfs_user,
         group=params.user_group,
         mode=0644,
         content=data_dir_to_mount_file_content
    )

  elif action == "start" or action == "stop":
    import params
    service(
      action=action, name="datanode",
      user=params.hdfs_user,
      create_pid_dir=True,
      create_log_dir=True
    )
  elif action == "status":
    import status_params
    check_process_status(status_params.datanode_pid_file)


@OsFamilyFuncImpl(os_family=OSConst.WINSRV_FAMILY)
def datanode(action=None):
  if action == "configure":
    pass
  elif(action == "start" or action == "stop"):
    import params
    Service(params.datanode_win_service_name, action=action)
  elif action == "status":
    import status_params
    check_windows_service_status(status_params.datanode_win_service_name)