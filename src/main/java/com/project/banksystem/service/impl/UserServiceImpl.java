package com.project.banksystem.service.impl;

import com.project.banksystem.config.JwtTokenProvider;
import com.project.banksystem.dto.*;
import com.project.banksystem.entity.Role;
import com.project.banksystem.entity.User;
import com.project.banksystem.repository.UserRepository;
import com.project.banksystem.utils.AccountUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.String;

@Service
@AllArgsConstructor
@NoArgsConstructor
public class UserServiceImpl implements UserService {
    @Autowired
    UserRepository userRepository;

    @Autowired
    EmailService emailService;

    @Autowired
    TransactionService transactionService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AuthenticationManager authenticationManager;

    @Autowired
    JwtTokenProvider jwtTokenProvider;

    @Override
    public BankResponse createAccount(UserRequest userRequest) {
        /*
          Creating an account - saving a new user into the DB
          Check if a User exist
         */
        if (userRepository.existsByEmail(userRequest.getEmail())) {
            return BankResponse.builder()
                    .responseCode(AccountUtils.ACCOUNT_EXISTS_CODE)
                    .responseMessage(AccountUtils.ACCOUNT_EXIST_MESSAGE)
                    .accountInfo(null)
                    .build();
        }
        String pass = AccountUtils.generateAccountPin();

        User newUser = User.builder()
                .firstName(userRequest.getFirstName())
                .lastName(userRequest.getLastName())
                .gender(userRequest.getGender())
                .address(userRequest.getAddress())
                .stateOfOrigin(userRequest.getStateOfOrigin())
                .accountNumber(AccountUtils.generateAccountNumber())
                .accountBalance(BigDecimal.ZERO)
                .email(userRequest.getEmail())
                .password(passwordEncoder.encode(pass))
                .phoneNumber(userRequest.getPhoneNumber())
                .alternativePhoneNumber(userRequest.getAlternativePhoneNumber())
                .status("ACTIVE")
                .role(Role.valueOf("ROLE_ADMIN"))
                .build();
        User savedUser = userRepository.save(newUser);
        // Send Email alert
        EmailDetails emailDetails = EmailDetails.builder()
                .recipient(savedUser.getEmail())
                .subject("ACCOUNT CREATION")
                .messageBody("Congratulations !!!" + " " + savedUser.getFirstName() + " " + savedUser.getLastName() + " Your account has been created.\n" +
                        "Your account details: \n" +
                        "Account Name: " + savedUser.getFirstName() + " " + savedUser.getLastName() + "\n" +
                        "Account Number: " + savedUser.getAccountNumber() + "\n" +
                        "PIN code (password): " + pass)
                .build();

        emailService.sendEmailAlert(emailDetails);
        return BankResponse.builder()
                .responseCode(AccountUtils.ACCOUNT_CREATION_SUCCESS)
                .responseMessage(AccountUtils.ACCOUNT_CREATION_MESSAGE)
                .accountInfo(AccountInfo.builder()
                        .accountBalance(savedUser.getAccountBalance())
                        .accountNumber(savedUser.getAccountNumber())
                        .accountName(savedUser.getFirstName() + " " + savedUser.getLastName())
                        .password(pass)
                        .build())
                .build();
    }

    // Login (auth)
    public BankResponse login(LoginDto loginDto) {
        Authentication authentication = null;
        authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginDto.getEmail(), loginDto.getPassword())
        );

        EmailDetails loginAlert = EmailDetails.builder()
                .subject("LOGIN NOTIFICATION")
                .recipient(loginDto.getEmail())
                .messageBody("You just logged into your account. If you did not initiate this request, please contact your bank")
                .build();
        User tmpUser = userRepository.findUserByEmail(loginDto.getEmail());
        emailService.sendEmailAlert(loginAlert);
        return BankResponse.builder()
                .responseCode("Login Success")
                .responseMessage(jwtTokenProvider.generateToken(authentication))
                .accountInfo(AccountInfo.builder()
                        .accountBalance(tmpUser.getAccountBalance())
                        .accountNumber(tmpUser.getAccountNumber())
                        .accountName(tmpUser.getFirstName() + " " + tmpUser.getLastName())
                        .status(tmpUser.getStatus())
                        .createdAt(tmpUser.getCreatedAt())
                        .build())
                .build();
    }

    //TODO: Balance Enquiry, name Enquiry, credit, debit, transfer
    @Override
    public BankResponse balanceEnquiry(EnquiryRequest request) {
        // Check if the provided account number exist in the DB
        boolean isAccountExist = userRepository.existsByAccountNumber(request.getAccountNumber());
        if (!isAccountExist) {
            return BankResponse.builder()
                    .responseCode(AccountUtils.ACCOUNT_NOT_EXIST_CODE)
                    .responseMessage(AccountUtils.ACCOUNT_NOT_EXIST_MESSAGE)
                    .accountInfo(null)
                    .build();
        }

        User foundUser = userRepository.findByAccountNumber(request.getAccountNumber());
        return BankResponse.builder()
                .responseCode(AccountUtils.ACCOUNT_FOUND_CODE)
                .responseMessage(AccountUtils.ACCOUNT_FOUND_MESSAGE)
                .accountInfo(AccountInfo.builder()
                        .accountBalance(foundUser.getAccountBalance())
                        .accountNumber(request.getAccountNumber())
                        .accountName(foundUser.getFirstName() + " " + foundUser.getLastName())
                        .createdAt(foundUser.getCreatedAt())
                        .build())
                .build();
    }

    @Override
    public String nameEnquiry(EnquiryRequest request) {
        boolean isAccountExist = userRepository.existsByAccountNumber(request.getAccountNumber());
        if (!isAccountExist) {
            return AccountUtils.ACCOUNT_NOT_EXIST_MESSAGE;
        }
        User foundUser = userRepository.findByAccountNumber(request.getAccountNumber());
        return  foundUser.getFirstName() + " " + foundUser.getLastName();
    }

    @Override
    public BankResponse creditAccount(CreditDebitRequest request) {
        // checking if the account exist
        boolean isAccountExist = userRepository.existsByAccountNumber(request.getAccountNumber());
        if (!isAccountExist) {
            return BankResponse.builder()
                    .responseCode(AccountUtils.ACCOUNT_NOT_EXIST_CODE)
                    .responseMessage(AccountUtils.ACCOUNT_NOT_EXIST_MESSAGE)
                    .accountInfo(null)
                    .build();
        }

        User userToCredit = userRepository.findByAccountNumber(request.getAccountNumber());
        userToCredit.setAccountBalance(userToCredit.getAccountBalance().add(request.getAmount()));
        userRepository.save(userToCredit);

        // save the transaction
        TransactionDto transactionDto = TransactionDto.builder()
                .accountNumber((userToCredit.getAccountNumber()))
                .transactionType("CREDIT")
                .amount(request.getAmount())
                .build();

        transactionService.saveTransaction(transactionDto);

        return BankResponse.builder()
                .responseCode(AccountUtils.ACCOUNT_CREDIT_SUCCESS_CODE)
                .responseMessage(AccountUtils.ACCOUNT_CREDIT_SUCCESS_MESSAGE)
                .accountInfo(AccountInfo.builder()
                        .accountName(userToCredit.getFirstName() + " " + userToCredit.getLastName())
                        .accountBalance(userToCredit.getAccountBalance())
                        .accountNumber(request.getAccountNumber())
                        .build())
                .build();
    }

    @Override
    public BankResponse debitAccount(CreditDebitRequest request) {
        // check if the account exists
        boolean isAccountExist = userRepository.existsByAccountNumber(request.getAccountNumber());
        if (!isAccountExist) {
            return BankResponse.builder()
                    .responseCode(AccountUtils.ACCOUNT_NOT_EXIST_CODE)
                    .responseMessage(AccountUtils.ACCOUNT_NOT_EXIST_MESSAGE)
                    .accountInfo(null)
                    .build();
        }

        // check if the amount you intend to withdraw is < current account balance
        User userToDebit = userRepository.findByAccountNumber(request.getAccountNumber());
        BigInteger currentBalance = userToDebit.getAccountBalance().toBigInteger();
        BigInteger debitAmount = request.getAmount().toBigInteger();

        if (currentBalance.intValue() < debitAmount.intValue()) {
            return BankResponse.builder()
                    .responseCode(AccountUtils.INSUFFICIENT_BALANCE_CODE)
                    .responseMessage(AccountUtils.INSUFFICIENT_BALANCE_MESSAGE)
                    .accountInfo(null)
                    .build();
        } else {
            userToDebit.setAccountBalance(userToDebit.getAccountBalance().subtract(request.getAmount()));
            userRepository.save(userToDebit);
            // Save transaction
            TransactionDto transactionDto = TransactionDto.builder()
                    .accountNumber((userToDebit.getAccountNumber()))
                    .transactionType("DEBIT")
                    .amount(request.getAmount())
                    .build();

            transactionService.saveTransaction(transactionDto);

            return BankResponse.builder()
                    .responseCode(AccountUtils.ACCOUNT_DEBIT_SUCCESS_CODE)
                    .responseMessage(AccountUtils.ACCOUNT_DEBIT_SUCCESS_MESSAGE)
                    .accountInfo(AccountInfo.builder()
                            .accountNumber(request.getAccountNumber())
                            .accountName(userToDebit.getFirstName() + " " + userToDebit.getLastName())
                            .accountBalance(userToDebit.getAccountBalance())
                            .build())
                    .build();
        }
    }

    private String extractSourceAccountNumberFromToken(String token) {
        // Assuming the token is a JWT
        Claims claims = Jwts.parser().setSigningKey(jwtSecret).parseClaimsJws(token).getBody();
        // Replace "sourceAccountNumber" with the actual claim key
        return claims.get("sourceAccountNumber", String.class);
    }

    @Value("${app.jwt-secret}") // Replace with your actual secret
    private String jwtSecret;

    @Override
    public BankResponse transfer(TransferRequest request, HttpHeaders headers) {
        // get the account to debit (check if the account exists)
        // check if the amount debited not more than the account balance
        // debit the account to credit
        // update the account
        boolean isDestinationAccountExists = userRepository.existsByAccountNumber(request.getDestinationAccountNumber());

        if (!isDestinationAccountExists) {
            return BankResponse.builder()
                    .responseCode(AccountUtils.ACCOUNT_NOT_EXIST_CODE)
                    .responseMessage(AccountUtils.ACCOUNT_NOT_EXIST_MESSAGE)
                    .accountInfo(null)
                    .build();
        }

        // Extract JWT token from headers
        String token = headers.getFirst(HttpHeaders.AUTHORIZATION);

        String sourceAccountNumber = extractSourceAccountNumberFromToken(token);

        User sourceAccount = userRepository.findByAccountNumber(sourceAccountNumber);
        System.out.println("xxxxxxxxxxxxxxxxxxxxxx" + sourceAccountNumber);
        if (request.getAmount().compareTo(sourceAccount.getAccountBalance()) > 0){
            return BankResponse.builder()
                    .responseCode(AccountUtils.INSUFFICIENT_BALANCE_CODE)
                    .responseMessage(AccountUtils.INSUFFICIENT_BALANCE_MESSAGE)
                    .accountInfo(null)
                    .build();
        }

        sourceAccount.setAccountBalance(sourceAccount.getAccountBalance().subtract(request.getAmount()));
        String sourceUsername = sourceAccount.getFirstName() + " " + sourceAccount.getLastName().toUpperCase();
        userRepository.save(sourceAccount);
        EmailDetails debitAlert = EmailDetails.builder()
                .subject("DEBIT ALERT")
                .recipient(sourceAccount.getEmail())
                .messageBody("The sum of " + request.getAmount() + " has been deducted from your account!\nYour current Balance is: " + sourceAccount.getAccountBalance())
                .build();

        emailService.sendEmailAlert(debitAlert);

        TransactionDto transactionDebit = TransactionDto.builder()
                .accountNumber((sourceAccount.getAccountNumber()))
                .transactionType("DEBIT")
                .senderReciever(sourceAccount.getFirstName()+" "+sourceAccount.getLastName())
                .amount(request.getAmount())
                .build();

        transactionService.saveTransaction(transactionDebit);

        User destinationAccount = userRepository.findByAccountNumber(request.getDestinationAccountNumber());
        destinationAccount.setAccountBalance(destinationAccount.getAccountBalance().add(request.getAmount()));
        userRepository.save(destinationAccount);

        EmailDetails creditAlert = EmailDetails.builder()
                .subject("CREDIT ALERT")
                .recipient(destinationAccount.getEmail())
                .messageBody("The sum of " + request.getAmount() + " has been added to your account from " + sourceUsername + "\nYour current Balance is: " + sourceAccount.getAccountBalance())
                .build();

        emailService.sendEmailAlert(creditAlert);

        TransactionDto transactionCredit = TransactionDto.builder()
                .accountNumber((destinationAccount.getAccountNumber()))
                .transactionType("CREDIT")
                .senderReciever(destinationAccount.getFirstName() + " " + destinationAccount.getLastName())
                .amount(request.getAmount())
                .build();

        transactionService.saveTransaction(transactionCredit);

        return  BankResponse.builder()
                .responseCode(AccountUtils.TRANSFER_SUCCESSFUL_CODE)
                .responseMessage(AccountUtils.TRANSFER_SUCCESSFUL_MESSAGE)
                .accountInfo(null)
                .build();
    }
}
