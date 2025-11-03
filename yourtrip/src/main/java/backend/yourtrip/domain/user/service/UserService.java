package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.*;
import backend.yourtrip.domain.user.dto.response.*;

public interface UserService {
    UserSignupResponse signup(UserSignupRequest request);
    UserLoginResponse login(UserLoginRequest request);
}