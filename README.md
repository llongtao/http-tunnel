# http-tunnel
主要作用是使用http搭建隧道的形式(1.4版本修改为websocket)从外网访问内网资源，监听本地端口代理内网资源

例：外网可以通过http访问内网服务器，那么使用此隧道即可通过http形式，从外网访问内网所有资源，如数据库、redis、k8s集群内部资源、ssh等
该项目是 https://github.com/nicolas-dutertry/htunnel 的派生分支，相比原版增加了多资源代理和账号准入的功能,客户端与服务端修改为websocket连接

1.4版本新特性
1. 使用websocket连接客户端与服务端,大大降低http轮询请求的性能损耗
2. 同时服务端变为无状态服务,可自由扩展
3. 资源准入修改为服务端控制,使用更安全

该项目spring-boot3 spring-native 项目,你也可以当作一个完整的spring-native demo项目

# 基本原理如下
     ----------------------------------------------------------------   -----------------------------------------------
    | Corporate Network                                              | | Internet                                      | 
    |                                                                | |                                               |
    |  --------------------------------------------                  | |                                               |
    | | Employee PC                                |                 | |                                               |
    | |                                            |                                                                   |
    | |  ------------   TCP/IP   ----------------  |                  WS    ----------------   TCP/IP   ------------   |
    | | |   Client   |--------->| htunnel-client |------------------------>| htunnel-server |--------->|inner Server|  |
    | |  ------------            ----------------  |                        ----------------            ------------   |
    | |                                            |                 | |                                               |
    |  --------------------------------------------                  | |                                               |
    |                                                                | |                                               |
     ----------------------------------------------------------------   -----------------------------------------------

## 构建
mvn clean install

该项目使用springboot3，如果想将客户端打包为exe，使用如下命令
mvn native:compile -Pnative -X -pl htunnel-client -DskipTests=true 

## 使用

### htunnel-server
部署在内网服务器
```
修改配置

#准入账号密码
auth.user.username1=password1
auth.user.username2=password2

#准入资源
#resource.map.mysql=192.168.0.25:3306
#resource.map.localssh=localhost:22

运行
java -jar htunnel-server-version.jar
```

    



### htunnel-client
在用户机器运行
```shell
修改配置


tunnel:
  #账号密码
  username: lilongtao
  password: xxxxxx
  tunnels:
      #监听本机端口
    - port: 8888
      #htunnel-server定义的资源名
      resource: git
      #htunnel-server地址
      server: ws://xx.com/message
      #其他按此规则添加
    - port: 3306
      resource: mysql
      server:  ws://xx.com/message
    - port: 13306
      resource: premysql
      server:  ws://xx.com/message
    - port: 22
      resource: ssh
      server:  ws://xx.com/message
      
#运行
java -jar htunnel-client-version.jar

#若使用native打包，直接运行htunnel-client.exe 配置文件使用同目录application.properties
```
