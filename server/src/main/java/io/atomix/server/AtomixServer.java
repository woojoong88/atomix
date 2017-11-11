/*
 * Copyright 2017-present Open Networking Foundation
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
package io.atomix.server;

import io.atomix.Atomix;
import io.atomix.cluster.Node;
import io.atomix.cluster.NodeId;
import io.atomix.messaging.Endpoint;
import io.atomix.messaging.netty.NettyMessagingService;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.ArgumentType;
import net.sourceforge.argparse4j.inf.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

/**
 * Atomix server.
 */
public class AtomixServer {
  private static final Logger LOGGER = LoggerFactory.getLogger(AtomixServer.class);

  public static void main(String[] args) throws Exception {
    ArgumentType<Node> nodeType = new ArgumentType<Node>() {
      @Override
      public Node convert(ArgumentParser argumentParser, Argument argument, String value) throws ArgumentParserException {
        String[] address = parseAddress(value);
        return Node.newBuilder()
            .withId(parseNodeId(address))
            .withEndpoint(parseEndpoint(address))
            .build();
      }
    };

    ArgumentType<File> fileType = new ArgumentType<File>() {
      @Override
      public File convert(ArgumentParser argumentParser, Argument argument, String value) throws ArgumentParserException {
        return new File(value);
      }
    };

    ArgumentParser parser = ArgumentParsers.newArgumentParser("AtomixServer")
        .defaultHelp(true)
        .description("Atomix server");
    parser.addArgument("node")
        .type(nodeType)
        .nargs("?")
        .metavar("NAME:HOST:PORT")
        .setDefault(Node.newBuilder()
            .withId(NodeId.from("local"))
            .withEndpoint(new Endpoint(InetAddress.getByName("127.0.0.1"), NettyMessagingService.DEFAULT_PORT))
            .build())
        .help("The local node info");
    parser.addArgument("--bootstrap", "-b")
        .nargs("*")
        .type(nodeType)
        .metavar("NAME:HOST:PORT")
        .required(false)
        .help("Bootstraps a new cluster");
    parser.addArgument("--http-port", "-p")
        .type(Integer.class)
        .metavar("PORT")
        .required(false)
        .setDefault(5678)
        .help("An optional HTTP server port");
    parser.addArgument("--data-dir", "-d")
        .type(fileType)
        .metavar("FILE")
        .required(false)
        .setDefault(new File(System.getProperty("user.dir"), "data"))
        .help("The server data directory");

    Namespace namespace = null;
    try {
      namespace = parser.parseArgs(args);
    } catch (ArgumentParserException e) {
      parser.handleError(e);
      System.exit(1);
    }

    Node localNode = namespace.get("node");
    List<Node> bootstrap = namespace.getList("bootstrap");
    if (bootstrap == null) {
      bootstrap = Collections.singletonList(localNode);
    }

    File dataDir = namespace.get("data_dir");
    Integer httpPort = namespace.getInt("http_port");

    LOGGER.info("node: {}", localNode);
    LOGGER.info("bootstrap: {}", bootstrap);
    LOGGER.info("data directory: {}", dataDir);

    Atomix atomix = Atomix.newBuilder()
        .withLocalNode(localNode)
        .withBootstrapNodes(bootstrap)
        .withDataDir(dataDir)
        .withHttpPort(httpPort)
        .build();

    atomix.open().join();

    synchronized (Atomix.class) {
      while (atomix.isOpen()) {
        Atomix.class.wait();
      }
    }
  }

  static String[] parseAddress(String address) {
    String[] parsed = address.split(":");
    if (parsed.length > 3) {
      throw new IllegalArgumentException("Malformed address " + address);
    }
    return parsed;
  }

  static NodeId parseNodeId(String[] address) {
    if (address.length == 3) {
      return NodeId.from(address[0]);
    } else if (address.length == 2) {
      try {
        InetAddress.getByName(address[0]);
      } catch (UnknownHostException e) {
        return NodeId.from(address[0]);
      }
      return NodeId.from(parseEndpoint(address).toString());
    } else {
      try {
        InetAddress.getByName(address[0]);
        return NodeId.from(parseEndpoint(address).toString());
      } catch (UnknownHostException e) {
        return NodeId.from(address[0]);
      }
    }
  }

  static Endpoint parseEndpoint(String[] address) {
    String host;
    int port;
    if (address.length == 3) {
      host = address[1];
      port = Integer.parseInt(address[2]);
    } else if (address.length == 2) {
      try {
        host = address[0];
        port = Integer.parseInt(address[1]);
      } catch (NumberFormatException e) {
        host = address[1];
        port = NettyMessagingService.DEFAULT_PORT;
      }
    } else {
      try {
        InetAddress.getByName(address[0]);
        host = address[0];
      } catch (UnknownHostException e) {
        host = "127.0.0.1";
      }
      port = NettyMessagingService.DEFAULT_PORT;
    }

    try {
      return new Endpoint(InetAddress.getByName(host), port);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("Failed to resolve host", e);
    }
  }
}
