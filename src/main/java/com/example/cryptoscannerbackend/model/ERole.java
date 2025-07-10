package com.example.cryptoscannerbackend.model;

public enum ERole {
    ROLE_USER,
    ROLE_MODERATOR, // Optional, if you need a middle tier
    ROLE_ADMIN,
    ROLE_TRIAL, // You might use this for users on trial
    ROLE_PREMIUM // You might use this for premium users
}
    