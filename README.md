# http-tunnel
主要作用是使用http搭建隧道的形式从外网访问内网资源，监听本地端口代理内网资源

例：外网可以通过http访问内网服务器，那么使用此隧道即可通过http形式，从外网访问内网所有资源，如数据库、redis、k8s集群内部资源、ssh等
该项目是 https://github.com/nicolas-dutertry/htunnel 的派生分支，相比原版增加了多资源代理和账号准入的功能

spring-boot3 spring-native 项目

# 基本原理如下
     ----------------------------------------------------------------   -----------------------------------------------
    | Corporate Network                                              | | Internet                                      | 
    |                                                                | |                                               |
    |  --------------------------------------------                  | |                                               |
    | | Employee PC                                |                 | |                                               |
    | |                                            |                                                                   |
    | |  ------------   TCP/IP   ----------------  | HTTP   -------  HTTP   ----------------   TCP/IP   ------------   |
    | | | SSH Client |--------->| htunnel-client |-------->| Proxy |------>| htunnel-server |--------->| SSH Server |  |
    | |  ------------            ----------------  |        -------         ----------------            ------------   |
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
#rsa密钥对public-key，privateKeyStr在客户端配置
public-key=you public-key
#准入账号密码
auth.user.username1=password1
auth.user.username2=password2

运行
java -jar htunnel-server-version.jar
```

    



### htunnel-client
在用户机器运行
```shell
修改配置
#账号密码
tunnel.username=lilongtao
tunnel.password=xxxxxx

#rsa密钥对 私钥，需要和server匹配
tunnel.privateKeyStr=xxxxx

#通过localhost:3000访问内网192.168.0.22:1234
#监听本机端口
tunnel.tunnels[0].port=3000 
#htunnel-server访问地址
tunnel.tunnels[0].server=http://xxx.com/ 
#被代理的内网目标ip:port
tunnel.tunnels[0].target=192.168.0.22:1234

#其他按此规则添加
tunnel.tunnels[1].port=3001
tunnel.tunnels[1].server=http://xxx.com/ 
tunnel.tunnels[1].target=192.168.0.22:1236

#运行
java -jar htunnel-client-version.jar

#若使用native打包，直接运行htunnel-client.exe 配置文件使用同目录application.properties
```
