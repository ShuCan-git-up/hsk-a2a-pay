// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {Test} from "forge-std/Test.sol";
import {A2ASettlement} from "../src/A2ASettlement.sol";
import {MockUSDC} from "../src/mocks/MockUSDC.sol";

contract A2ASettlementTest is Test {
    A2ASettlement reg;
    MockUSDC usdc;
    address owner = makeAddr("owner");
    address receiver = makeAddr("receiver");
    address operator = makeAddr("operator");
    address payTo = makeAddr("payTo");
    address payer = makeAddr("payer");
    bytes32 constant ID = keccak256("CART-1");
    bytes32 constant SVC = keccak256("wallet-insight");

    function setUp() public {
        reg = new A2ASettlement(owner);
        usdc = new MockUSDC();
        usdc.mint(payer, 100e6);
    }

    function _publish(uint256 amount) internal {
        vm.prank(receiver);
        reg.publishTerms(ID, payTo, address(usdc), amount, uint64(block.timestamp + 1 hours), SVC);
    }

    function test_publishAndRecordHsp() public {
        _publish(1e6);
        assertEq(uint8(reg.statusOf(ID)), uint8(A2ASettlement.Status.Open));
        vm.prank(receiver);
        reg.recordHspSettlement(ID, payer, 1e6, keccak256("PAY-REQ-1"), bytes32(uint256(0xabc)));
        A2ASettlement.Terms memory t = reg.getTerms(ID);
        assertEq(uint8(t.status), uint8(A2ASettlement.Status.SettledViaHsp));
        assertEq(t.payer, payer);
        assertEq(t.settlementTx, bytes32(uint256(0xabc)));
        assertEq(reg.settledCount(), 1);
    }

    function test_operatorCanActForAgent() public {
        vm.prank(receiver);
        reg.setOperator(operator, true);
        vm.prank(operator);
        reg.publishTermsFor(receiver, ID, payTo, address(usdc), 1e6, uint64(block.timestamp + 1 hours), SVC);
        vm.prank(operator);
        reg.recordHspSettlement(ID, payer, 1e6, bytes32(0), bytes32(0));
        assertEq(reg.getTerms(ID).receiverAgent, receiver);
    }

    function test_strangerCannotRecord() public {
        _publish(1e6);
        vm.expectRevert(abi.encodeWithSelector(A2ASettlement.NotAuthorized.selector, ID, address(this)));
        reg.recordHspSettlement(ID, payer, 1e6, bytes32(0), bytes32(0));
    }

    function test_underpaidHspRejected() public {
        _publish(2e6);
        vm.prank(receiver);
        vm.expectRevert(A2ASettlement.InvalidAmount.selector);
        reg.recordHspSettlement(ID, payer, 1e6, bytes32(0), bytes32(0));
    }

    function test_duplicateTermsRejected() public {
        _publish(1e6);
        vm.prank(receiver);
        vm.expectRevert(abi.encodeWithSelector(A2ASettlement.TermsExist.selector, ID));
        reg.publishTerms(ID, payTo, address(usdc), 1e6, uint64(block.timestamp + 1 hours), SVC);
    }

    function test_payDirectMovesFundsToPayee() public {
        _publish(5e6);
        vm.startPrank(payer);
        usdc.approve(address(reg), 5e6);
        reg.payDirect(ID);
        vm.stopPrank();
        assertEq(usdc.balanceOf(payTo), 5e6);
        assertEq(usdc.balanceOf(address(reg)), 0);
        assertEq(uint8(reg.statusOf(ID)), uint8(A2ASettlement.Status.SettledDirect));
    }

    function test_payDirectExpired() public {
        _publish(1e6);
        vm.warp(block.timestamp + 2 hours);
        vm.prank(payer);
        vm.expectRevert(abi.encodeWithSelector(A2ASettlement.TermsExpired.selector, ID));
        reg.payDirect(ID);
    }

    function test_spendPolicyCaps() public {
        vm.startPrank(payer);
        reg.setSpendPolicy(2e6, 3e6, true);
        vm.stopPrank();
        (bool ok, string memory why) = reg.checkSpend(payer, payTo, 1e6);
        assertFalse(ok);
        assertEq(why, "merchant not allowed");
        vm.prank(payer);
        reg.setMerchantAllowed(payTo, true);
        (ok,) = reg.checkSpend(payer, payTo, 1e6);
        assertTrue(ok);
        (ok, why) = reg.checkSpend(payer, payTo, 3e6);
        assertFalse(ok);
        assertEq(why, "over per-payment cap");

        // two settlements of 1.5 exceed the 3.0 daily cap on the third
        _publish(15e5);
        vm.prank(receiver);
        reg.recordHspSettlement(ID, payer, 15e5, bytes32(0), bytes32(0));
        vm.prank(receiver);
        reg.publishTerms(keccak256("CART-2"), payTo, address(usdc), 15e5, uint64(block.timestamp + 1 hours), SVC);
        vm.prank(receiver);
        reg.recordHspSettlement(keccak256("CART-2"), payer, 15e5, bytes32(0), bytes32(0));
        (ok, why) = reg.checkSpend(payer, payTo, 1);
        assertFalse(ok);
        assertEq(why, "over daily cap");
        vm.warp(block.timestamp + 1 days);
        (ok,) = reg.checkSpend(payer, payTo, 1e6);
        assertTrue(ok);
    }

    function test_payDirectRespectsPolicy() public {
        vm.prank(payer);
        reg.setSpendPolicy(1e6, 1e6, false);
        _publish(2e6);
        vm.startPrank(payer);
        usdc.approve(address(reg), 2e6);
        vm.expectRevert(abi.encodeWithSelector(A2ASettlement.SpendRejected.selector, "over per-payment cap"));
        reg.payDirect(ID);
        vm.stopPrank();
    }

    function test_cancelAndPause() public {
        _publish(1e6);
        vm.prank(receiver);
        reg.cancelTerms(ID);
        assertEq(uint8(reg.statusOf(ID)), uint8(A2ASettlement.Status.Cancelled));
        vm.prank(owner);
        reg.pause();
        vm.prank(receiver);
        vm.expectRevert();
        reg.publishTerms(keccak256("X"), payTo, address(usdc), 1e6, uint64(block.timestamp + 1 hours), SVC);
    }

    function test_rejectsNative() public {
        vm.deal(address(this), 1 ether);
        (bool ok,) = address(reg).call{value: 1}("");
        assertFalse(ok);
    }

    function testFuzz_expiryWindow(uint64 dt) public {
        vm.assume(dt > 30 days && dt < type(uint32).max);
        vm.prank(receiver);
        vm.expectRevert();
        reg.publishTerms(ID, payTo, address(usdc), 1e6, uint64(block.timestamp) + dt, SVC);
    }
}
