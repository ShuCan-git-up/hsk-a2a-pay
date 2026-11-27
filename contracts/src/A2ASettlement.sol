// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {Pausable} from "@openzeppelin/contracts/utils/Pausable.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

/// @title A2ASettlement
/// @notice On-chain ledger for agent-to-agent (A2A) payments on HashKey Chain.
///
/// Flow:
///  1. A receiving agent publishes its payment terms (price, token, payee, expiry, service hash).
///  2. The paying agent checks the payer's on-chain spend policy with `checkSpend` before it signs anything.
///  3. Payment is executed through HSP (HashKey Settlement Platform). After the receiving agent has verified the
///     payment with HSP, it anchors a settlement receipt here with `recordHspSettlement`.
///  4. Fallback rail: the payer can settle directly in ERC-20 with `payDirect` (non-custodial, funds go straight
///     to the payee; this contract never holds tokens).
///
/// The owner can only pause. It cannot move funds or rewrite terms.
contract A2ASettlement is ReentrancyGuard, Pausable, Ownable {
    using SafeERC20 for IERC20;

    enum Status {
        None,
        Open,
        SettledViaHsp,
        SettledDirect,
        Cancelled
    }

    struct Terms {
        address receiverAgent; // agent that published the terms (and may record the settlement)
        uint64 expiresAt;
        uint64 createdAt;
        Status status;
        address payTo; // payee wallet
        address token; // settlement token (e.g. USDC on HashKey Chain)
        uint128 amount; // token raw units
        address payer; // set on settlement
        bytes32 serviceHash; // keccak256 of the service/resource description
        bytes32 hspPaymentRef; // keccak256(HSP payment_request_id) for HSP settlements
        bytes32 settlementTx; // tx hash reported by HSP (HSP broadcasts the token transfer)
    }

    struct SpendPolicy {
        uint128 maxPerPayment; // 0 = policy not configured
        uint128 dailyCap;
        uint128 spentToday;
        uint64 day; // block.timestamp / 1 days of `spentToday`
        bool merchantAllowlistOn;
    }

    uint64 public constant MAX_TERMS_WINDOW = 30 days;

    mapping(bytes32 termsId => Terms) private _terms;
    mapping(address agent => mapping(address operator => bool)) public isOperator;
    mapping(address payer => SpendPolicy) private _policies;
    mapping(address payer => mapping(address payTo => bool)) public allowedMerchant;

    uint256 public termsCount;
    uint256 public settledCount;

    event TermsPublished(
        bytes32 indexed termsId,
        address indexed receiverAgent,
        address indexed payTo,
        address token,
        uint256 amount,
        uint64 expiresAt,
        bytes32 serviceHash
    );
    event HspSettlementRecorded(
        bytes32 indexed termsId, address indexed payer, address indexed payTo, uint256 amount, bytes32 hspPaymentRef, bytes32 settlementTx
    );
    event DirectSettlement(bytes32 indexed termsId, address indexed payer, address indexed payTo, uint256 amount);
    event TermsCancelled(bytes32 indexed termsId);
    event OperatorSet(address indexed agent, address indexed operator, bool approved);
    event SpendPolicySet(address indexed payer, uint256 maxPerPayment, uint256 dailyCap, bool merchantAllowlistOn);
    event MerchantAllowed(address indexed payer, address indexed payTo, bool allowed);

    error ZeroAddress();
    error InvalidAmount();
    error InvalidExpiry(uint64 expiresAt);
    error TermsExist(bytes32 termsId);
    error TermsNotFound(bytes32 termsId);
    error TermsNotOpen(bytes32 termsId);
    error TermsExpired(bytes32 termsId);
    error NotAuthorized(bytes32 termsId, address caller);
    error SpendRejected(string reason);
    error NativeNotAccepted();

    constructor(address initialOwner) Ownable(initialOwner) {}

    receive() external payable {
        revert NativeNotAccepted();
    }

    // ---------------------------------------------------------------- receiving agent

    function setOperator(address operator, bool approved) external {
        if (operator == address(0)) revert ZeroAddress();
        isOperator[msg.sender][operator] = approved;
        emit OperatorSet(msg.sender, operator, approved);
    }

    function publishTerms(
        bytes32 termsId,
        address payTo,
        address token,
        uint256 amount,
        uint64 expiresAt,
        bytes32 serviceHash
    ) external whenNotPaused {
        _publish(msg.sender, termsId, payTo, token, amount, expiresAt, serviceHash);
    }

    /// @notice An operator (e.g. the agent's hot key) publishes on behalf of a receiving agent.
    function publishTermsFor(
        address receiverAgent,
        bytes32 termsId,
        address payTo,
        address token,
        uint256 amount,
        uint64 expiresAt,
        bytes32 serviceHash
    ) external whenNotPaused {
        if (!isOperator[receiverAgent][msg.sender]) revert NotAuthorized(termsId, msg.sender);
        _publish(receiverAgent, termsId, payTo, token, amount, expiresAt, serviceHash);
    }

    /// @notice Anchor an HSP settlement after the receiving agent verified it with HSP (query_payment).
    function recordHspSettlement(bytes32 termsId, address payer, uint256 amountPaid, bytes32 hspPaymentRef, bytes32 settlementTx)
        external
        whenNotPaused
    {
        Terms storage t = _requireOpen(termsId);
        _requireAgent(t, termsId);
        if (payer == address(0)) revert ZeroAddress();
        if (amountPaid < t.amount) revert InvalidAmount();
        t.status = Status.SettledViaHsp;
        t.payer = payer;
        t.hspPaymentRef = hspPaymentRef;
        t.settlementTx = settlementTx;
        settledCount++;
        _consume(payer, t.amount);
        emit HspSettlementRecorded(termsId, payer, t.payTo, amountPaid, hspPaymentRef, settlementTx);
    }

    function cancelTerms(bytes32 termsId) external {
        Terms storage t = _requireOpen(termsId);
        _requireAgent(t, termsId);
        t.status = Status.Cancelled;
        emit TermsCancelled(termsId);
    }

    // ---------------------------------------------------------------- paying agent / payer

    /// @notice Payer-side guardrail the paying agent must respect. Set by the payer wallet itself.
    function setSpendPolicy(uint128 maxPerPayment, uint128 dailyCap, bool merchantAllowlistOn) external {
        if (maxPerPayment == 0 || dailyCap < maxPerPayment) revert InvalidAmount();
        SpendPolicy storage p = _policies[msg.sender];
        p.maxPerPayment = maxPerPayment;
        p.dailyCap = dailyCap;
        p.merchantAllowlistOn = merchantAllowlistOn;
        emit SpendPolicySet(msg.sender, maxPerPayment, dailyCap, merchantAllowlistOn);
    }

    function setMerchantAllowed(address payTo, bool allowed) external {
        if (payTo == address(0)) revert ZeroAddress();
        allowedMerchant[msg.sender][payTo] = allowed;
        emit MerchantAllowed(msg.sender, payTo, allowed);
    }

    /// @notice Would the payer's policy allow paying `amount` to `payTo` right now? Payers without a policy pass.
    function checkSpend(address payer, address payTo, uint256 amount) public view returns (bool ok, string memory reason) {
        SpendPolicy storage p = _policies[payer];
        if (p.maxPerPayment == 0) return (true, "no policy");
        if (amount > p.maxPerPayment) return (false, "over per-payment cap");
        uint256 spent = p.day == _today() ? p.spentToday : 0;
        if (spent + amount > p.dailyCap) return (false, "over daily cap");
        if (p.merchantAllowlistOn && !allowedMerchant[payer][payTo]) return (false, "merchant not allowed");
        return (true, "ok");
    }

    /// @notice Fallback rail without HSP: pay the terms directly in the settlement token.
    function payDirect(bytes32 termsId) external nonReentrant whenNotPaused {
        Terms storage t = _requireOpen(termsId);
        if (block.timestamp > t.expiresAt) revert TermsExpired(termsId);
        (bool ok, string memory reason) = checkSpend(msg.sender, t.payTo, t.amount);
        if (!ok) revert SpendRejected(reason);
        t.status = Status.SettledDirect;
        t.payer = msg.sender;
        settledCount++;
        _consume(msg.sender, t.amount);
        IERC20(t.token).safeTransferFrom(msg.sender, t.payTo, t.amount);
        emit DirectSettlement(termsId, msg.sender, t.payTo, t.amount);
    }

    // ---------------------------------------------------------------- views

    function getTerms(bytes32 termsId) external view returns (Terms memory) {
        return _terms[termsId];
    }

    function statusOf(bytes32 termsId) external view returns (Status) {
        return _terms[termsId].status;
    }

    function policyOf(address payer) external view returns (SpendPolicy memory p) {
        p = _policies[payer];
        if (p.day != _today()) p.spentToday = 0;
    }

    // ---------------------------------------------------------------- admin

    function pause() external onlyOwner {
        _pause();
    }

    function unpause() external onlyOwner {
        _unpause();
    }

    // ---------------------------------------------------------------- internal

    function _publish(
        address agent,
        bytes32 termsId,
        address payTo,
        address token,
        uint256 amount,
        uint64 expiresAt,
        bytes32 serviceHash
    ) internal {
        if (payTo == address(0) || token == address(0)) revert ZeroAddress();
        if (amount == 0 || amount > type(uint128).max) revert InvalidAmount();
        if (expiresAt <= block.timestamp || expiresAt > block.timestamp + MAX_TERMS_WINDOW) revert InvalidExpiry(expiresAt);
        if (_terms[termsId].status != Status.None) revert TermsExist(termsId);
        _terms[termsId] = Terms({
            receiverAgent: agent,
            expiresAt: expiresAt,
            createdAt: uint64(block.timestamp),
            status: Status.Open,
            payTo: payTo,
            token: token,
            amount: uint128(amount),
            payer: address(0),
            serviceHash: serviceHash,
            hspPaymentRef: bytes32(0),
            settlementTx: bytes32(0)
        });
        termsCount++;
        emit TermsPublished(termsId, agent, payTo, token, amount, expiresAt, serviceHash);
    }

    function _requireOpen(bytes32 termsId) internal view returns (Terms storage t) {
        t = _terms[termsId];
        if (t.status == Status.None) revert TermsNotFound(termsId);
        if (t.status != Status.Open) revert TermsNotOpen(termsId);
    }

    function _requireAgent(Terms storage t, bytes32 termsId) internal view {
        if (msg.sender != t.receiverAgent && !isOperator[t.receiverAgent][msg.sender]) {
            revert NotAuthorized(termsId, msg.sender);
        }
    }

    function _consume(address payer, uint256 amount) internal {
        SpendPolicy storage p = _policies[payer];
        if (p.maxPerPayment == 0) return;
        uint64 today = _today();
        if (p.day != today) {
            p.day = today;
            p.spentToday = 0;
        }
        p.spentToday += uint128(amount);
    }

    function _today() internal view returns (uint64) {
        return uint64(block.timestamp / 1 days);
    }
}
