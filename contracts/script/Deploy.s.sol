// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {Script, console2} from "forge-std/Script.sol";
import {A2ASettlement} from "../src/A2ASettlement.sol";

/// forge script script/Deploy.s.sol --rpc-url hsk_testnet --broadcast --private-key $DEPLOYER_PRIVATE_KEY
contract Deploy is Script {
    function run() external returns (A2ASettlement reg) {
        address owner = vm.envOr("SETTLEMENT_OWNER", msg.sender);
        vm.startBroadcast();
        reg = new A2ASettlement(owner);
        vm.stopBroadcast();
        console2.log("A2ASettlement", address(reg));
        console2.log("chainId", block.chainid);
    }
}
